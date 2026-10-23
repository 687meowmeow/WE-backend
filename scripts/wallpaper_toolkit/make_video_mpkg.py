#!/usr/bin/env python3
"""
Build a Wallpaper Engine .mpkg file for Video-type wallpapers.

The Video .mpkg includes:
  - project.json (type:"video", file:"wallpaper.mp4")
  - wallpaper.mp4 (H.264 video, MP4 container, looping)
  - preview.jpg (preview thumbnail)

The WE Android app's VideoWallpaperView → VideoDrawable code path
correctly plays .mp4 files from inside the .mpkg VFS using:
  lib.getWallpaperFileVirtualOffset(file) → MPKGVideoReader → MediaPlayer

This produces a .mpkg that the WE Android app plays via the same
MediaPlayer path it uses for real WE Video wallpapers.
"""
import argparse
import io
import json
import os
import struct
import subprocess
import sys
from pathlib import Path

from PIL import Image

# Import .mpkg packer (shared with scene packer)
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, SCRIPT_DIR)
from make_scene_mpkg import pack_mpkg


def make_preview_from_video(video_path, output_jpg, preview_size=400, time_offset=1.0):
    """Extract a thumbnail from a video at time_offset using ffmpeg."""
    cmd = [
        'ffmpeg', '-y', '-ss', str(time_offset), '-i', video_path,
        '-vframes', '1', '-vf', f'scale=\'min({preview_size},iw)\':-2',
        '-q:v', '2', output_jpg,
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        # ffmpeg failed - use a black preview
        img = Image.new('RGB', (preview_size, preview_size), (0, 0, 0))
        img.save(output_jpg, 'JPEG', quality=85)
    return output_jpg


def make_video_mp4(source_path, output_path, max_width=1920, fps=30, crf=23):
    """Re-encode video to H.264 MP4 for Wallpaper Engine.

    WE expects:
      - MP4 container
      - H.264 video codec (yuv420p)
      - AAC audio (optional, can be muted)
      - Square Pixel Aspect Ratio (SAR 1:1)
      - Profile: Baseline or Main (most compatible)

    Args:
        source_path: source video file
        output_path: where to write the H.264 MP4
        max_width: max video width (will downscale if source is larger)
        fps: target FPS
        crf: constant rate factor (lower = better quality, larger file)

    Returns:
        dict with: width, height, duration
    """
    cmd = [
        'ffmpeg', '-y', '-i', source_path,
        # Video: H.264, yuv420p (compatible with all devices), scale to max_width
        '-vf', f'scale=\'min({max_width},iw)\':-2,format=yuv420p,setsar=1',
        '-c:v', 'libx264', '-preset', 'fast', '-crf', str(crf),
        '-profile:v', 'baseline',  # most compatible profile
        '-r', str(fps),
        '-pix_fmt', 'yuv420p',
        # Audio: AAC, low bitrate (most wallpapers are silent or ambient)
        '-c:a', 'aac', '-b:a', '96k',
        # MP4 container with faststart for streaming
        '-movflags', '+faststart',
        '-f', 'mp4',
        output_path,
    ]
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=600)
    if result.returncode != 0:
        raise RuntimeError(f"ffmpeg failed:\n{result.stderr}")

    # Get video dimensions and duration with ffprobe
    probe_cmd = [
        'ffprobe', '-v', 'error',
        '-select_streams', 'v:0',
        '-show_entries', 'stream=width,height,duration',
        '-of', 'json',
        output_path,
    ]
    probe = subprocess.run(probe_cmd, capture_output=True, text=True)
    if probe.returncode == 0:
        try:
            info = json.loads(probe.stdout)
            streams = info.get('streams', [])
            if streams:
                stream = streams[0]
                return {
                    'width': int(stream.get('width', 0)),
                    'height': int(stream.get('height', 0)),
                    'duration': float(stream.get('duration', 0)),
                }
        except (json.JSONDecodeError, ValueError):
            pass
    return {'width': 0, 'height': 0, 'duration': 0}


def build_video_mpkg(source_path, output_path, title="WepWawet Video Wallpaper",
                     description="Sent from WepWawet", tags=None,
                     workshop_url=None, max_width=1920, fps=30, crf=23,
                     preview_size=400, keep_source_codec=False):
    """Build a complete Video-type .mpkg from a video file.

    Args:
        source_path: source video (MP4, MOV, AVI, WebM, GIF, etc.)
        output_path: where to write the .mpkg file
        title: wallpaper title
        description: wallpaper description
        tags: list of tags (default ["Anime", "Video"])
        workshop_url: optional Steam workshop URL
        max_width: max video width (downscale if larger)
        fps: target FPS
        crf: H.264 CRF (lower = better quality)
        preview_size: max dimension of preview thumbnail
        keep_source_codec: if True, skip ffmpeg re-encoding (use source as-is)

    Returns:
        dict with: mpkg_size, video_size, video_dims, duration, entries
    """
    if tags is None:
        tags = ["Anime", "Video"]

    # Step 1: prepare the wallpaper.mp4 file
    mp4_path = output_path + '.tmp.mp4'
    try:
        if keep_source_codec and source_path.lower().endswith('.mp4'):
            # Source is already MP4 - use as-is
            import shutil
            shutil.copy(source_path, mp4_path)
            video_info = {'width': 0, 'height': 0, 'duration': 0}
            # Probe the video
            probe = subprocess.run([
                'ffprobe', '-v', 'error', '-select_streams', 'v:0',
                '-show_entries', 'stream=width,height,duration',
                '-of', 'json', mp4_path
            ], capture_output=True, text=True)
            if probe.returncode == 0:
                try:
                    info = json.loads(probe.stdout)
                    streams = info.get('streams', [])
                    if streams:
                        s = streams[0]
                        video_info = {
                            'width': int(s.get('width', 0)),
                            'height': int(s.get('height', 0)),
                            'duration': float(s.get('duration', 0)),
                        }
                except Exception:
                    pass
        else:
            video_info = make_video_mp4(source_path, mp4_path, max_width=max_width, fps=fps, crf=crf)

        with open(mp4_path, 'rb') as f:
            video_bytes = f.read()
    finally:
        if os.path.exists(mp4_path):
            os.remove(mp4_path)

    # Step 2: extract a preview thumbnail from the video
    preview_jpg_path = output_path + '.tmp.preview.jpg'
    try:
        make_preview_from_video(source_path, preview_jpg_path, preview_size=preview_size)
        with open(preview_jpg_path, 'rb') as f:
            preview_bytes = f.read()
    finally:
        if os.path.exists(preview_jpg_path):
            os.remove(preview_jpg_path)

    # Step 3: build project.json (type:"video", file:"wallpaper.mp4")
    project_json = json.dumps({
        "contentrating": "Everyone",
        "description": description,
        "file": "wallpaper.mp4",
        "general": {
            "properties": {
                "schemecolor": {
                    "order": 0,
                    "text": "ui_browse_properties_scheme_color",
                    "type": "color",
                    "value": "0.000 0.000 0.000",
                }
            }
        },
        "preview": "preview.jpg",
        "tags": tags,
        "title": title,
        "type": "video",
        "version": 0,
        "visibility": "private",
        "workshopurl": workshop_url or "https://steamcommunity.com/sharedfiles/filedetails/?id=0",
    }, indent='\t', ensure_ascii=False, separators=(',', ' : ')).encode('utf-8')

    # Step 4: assemble .mpkg entries
    entries = [
        ("project.json", project_json),
        ("wallpaper.mp4", video_bytes),
        ("preview.jpg", preview_bytes),
    ]

    mpkg_size = pack_mpkg(output_path, entries)

    return {
        'mpkg_size': mpkg_size,
        'video_size': len(video_bytes),
        'preview_size': len(preview_bytes),
        'video_dims': (video_info.get('width', 0), video_info.get('height', 0)),
        'duration': video_info.get('duration', 0),
        'entries': [(name, len(blob)) for name, blob in entries],
    }


def main():
    parser = argparse.ArgumentParser(description='Build a Video-type .mpkg from a video')
    parser.add_argument('input', help='Input video (MP4, MOV, WebM, GIF, etc.)')
    parser.add_argument('output', help='Output .mpkg file path')
    parser.add_argument('--title', '-t', default='WepWawet Video Wallpaper')
    parser.add_argument('--description', '-d', default='Sent from WepWawet')
    parser.add_argument('--tags', default='Anime,Video')
    parser.add_argument('--url', help='Steam workshop URL')
    parser.add_argument('--max-width', '-w', type=int, default=1920)
    parser.add_argument('--fps', '-r', type=int, default=30)
    parser.add_argument('--crf', '-c', type=int, default=23)
    parser.add_argument('--preview-size', '-p', type=int, default=400)
    parser.add_argument('--keep-source', action='store_true',
                       help='Use source video as-is (skip re-encoding; only works for MP4 input)')
    args = parser.parse_args()

    tags = [t.strip() for t in args.tags.split(',') if t.strip()]
    print(f"Building Video .mpkg from {args.input} → {args.output}")
    result = build_video_mpkg(
        args.input, args.output,
        title=args.title, description=args.description, tags=tags,
        workshop_url=args.url, max_width=args.max_width, fps=args.fps, crf=args.crf,
        preview_size=args.preview_size, keep_source_codec=args.keep_source,
    )
    print(f"\nResult:")
    print(f"  .mpkg size: {result['mpkg_size']:,} bytes")
    print(f"  Video size: {result['video_size']:,} bytes")
    print(f"  Preview size: {result['preview_size']:,} bytes")
    print(f"  Video dims: {result['video_dims'][0]}x{result['video_dims'][1]}")
    print(f"  Duration: {result['duration']:.1f}s")
    print(f"\n  Entries:")
    for name, sz in result['entries']:
        print(f"    {name:40s}  {sz:>12,} bytes")


if __name__ == '__main__':
    main()
