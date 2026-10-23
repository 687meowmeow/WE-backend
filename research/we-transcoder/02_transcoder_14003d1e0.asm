; wallpaperui.exe  --  mobile wallpaper transcoder, function 0x14003d1e0-0x140042434 (21,076 bytes)
; NOT present in the Ghidra C dump. Disassembled from the PE (x64, image base 0x140000000).
; String literals and imported API names are annotated. Located via .pdata and xrefs to
; "-transcode -i", "MPKG_VFS://", "scene.pkg", "wallpaper.mp4", "mp4_h264", "texturereduction".
; Flow (ordered string refs): project.json -> "file" extension (.mp4/.webm | .json) -> copy files ->
; preview/thumbnail -> scene.pkg unpack -> wallpaper.mp4 (mp4_h264) -> .tex ->
; resourcecompiler64.exe -transcode -f ETC2 -c force -shrink N -maxmipmaps 1 -> shaders/zcompat ->
; project.json rewrite.
14003d1e0:  mov    qword ptr [rsp + 0x2e58], r12
14003d1e8:  mov    rcx, rbx
14003d1eb:  mov    qword ptr [rsp + 0x2e50], r14
14003d1f3:  mov    qword ptr [rsp + 0x2e48], r15
14003d1fb:  movaps xmmword ptr [rsp + 0x2e30], xmm6
14003d203:  movaps xmmword ptr [rsp + 0x2e20], xmm7
14003d20b:  movaps xmmword ptr [rsp + 0x2e10], xmm8
14003d214:  movaps xmmword ptr [rsp + 0x2e00], xmm9
14003d21d:  movaps xmmword ptr [rsp + 0x2df0], xmm10
14003d226:  movaps xmmword ptr [rsp + 0x2de0], xmm11
14003d22f:  movaps xmmword ptr [rsp + 0x2dd0], xmm12
14003d238:  call   0x14000d880
14003d23d:  mov    rcx, rax
14003d240:  lea    rdx, [rip + 0xa759cd]   ; "temp"
14003d247:  call   0x14000d880
14003d24c:  mov    rcx, rax
14003d24f:  lea    rdx, [rip + 0xa78342]   ; "publish"
14003d256:  call   0x14000d880
14003d25b:  mov    rdx, rax
14003d25e:  lea    rcx, [rbp + 0x990]
14003d265:  call   0x140071c40
14003d26a:  lea    rcx, [rbp + 0x1650]
14003d271:  call   0x140071e80
14003d276:  mov    rdx, r13
14003d279:  lea    rcx, [rbp + 0x5f8]
14003d280:  call   0x14000c300
14003d285:  mov    qword ptr [rbp + 0x38], rsi
14003d289:  xorps  xmm0, xmm0
14003d28c:  movups xmmword ptr [rbp + 0x28], xmm0
14003d290:  mov    qword ptr [rbp + 0x40], rsi
14003d294:  movups xmm0, xmmword ptr [rax]
14003d297:  movups xmmword ptr [rbp + 0x28], xmm0
14003d29b:  movups xmm1, xmmword ptr [rax + 0x10]
14003d29f:  movups xmmword ptr [rbp + 0x38], xmm1
14003d2a3:  mov    qword ptr [rax + 0x10], rsi
14003d2a7:  mov    qword ptr [rax + 0x18], 7
14003d2af:  mov    word ptr [rax], si
14003d2b2:  mov    rax, qword ptr [rbp + 0x610]
14003d2b9:  cmp    rax, 7
14003d2bd:  jbe    0x14003d2fc
14003d2bf:  mov    rcx, qword ptr [rbp + 0x5f8]
14003d2c6:  lea    rdx, [rax*2 + 2]
14003d2ce:  cmp    rdx, 0x1000
14003d2d5:  jb     0x14003d2f7
14003d2d7:  mov    r8, qword ptr [rcx - 8]
14003d2db:  sub    rcx, r8
14003d2de:  sub    rcx, 8
14003d2e2:  cmp    rcx, 0x1f
14003d2e6:  ja     0x14004215d
14003d2ec:  lea    rdx, [rax*2 + 0x29]
14003d2f4:  mov    rcx, r8
14003d2f7:  call   0x14052e6f0
14003d2fc:  lea    rdx, [r13 + 0x20]
14003d300:  mov    qword ptr [rbp + 0x608], rsi
14003d307:  lea    rcx, [rbp + 0x618]
14003d30e:  mov    qword ptr [rbp + 0x610], 7
14003d319:  mov    word ptr [rbp + 0x5f8], si
14003d320:  call   0x14000c300
14003d325:  mov    qword ptr [rbp + 0x2d0], rsi
14003d32c:  xorps  xmm0, xmm0
14003d32f:  movups xmmword ptr [rbp + 0x2c0], xmm0
14003d336:  mov    qword ptr [rbp + 0x2d8], rsi
14003d33d:  movups xmm0, xmmword ptr [rax]
14003d340:  movups xmmword ptr [rbp + 0x2c0], xmm0
14003d347:  movups xmm1, xmmword ptr [rax + 0x10]
14003d34b:  movups xmmword ptr [rbp + 0x2d0], xmm1
14003d352:  mov    qword ptr [rax + 0x10], rsi
14003d356:  mov    qword ptr [rax + 0x18], 7
14003d35e:  mov    word ptr [rax], si
14003d361:  mov    rax, qword ptr [rbp + 0x630]
14003d368:  cmp    rax, 7
14003d36c:  jbe    0x14003d3ab
14003d36e:  mov    rcx, qword ptr [rbp + 0x618]
14003d375:  lea    rdx, [rax*2 + 2]
14003d37d:  cmp    rdx, 0x1000
14003d384:  jb     0x14003d3a6
14003d386:  mov    r8, qword ptr [rcx - 8]
14003d38a:  sub    rcx, r8
14003d38d:  sub    rcx, 8
14003d391:  cmp    rcx, 0x1f
14003d395:  ja     0x14004215d
14003d39b:  lea    rdx, [rax*2 + 0x29]
14003d3a3:  mov    rcx, r8
14003d3a6:  call   0x14052e6f0
14003d3ab:  xorps  xmm0, xmm0
14003d3ae:  mov    qword ptr [rbp + 0x628], rsi
14003d3b5:  lea    r15, [r13 + 0xf0]
14003d3bc:  mov    qword ptr [rbp + 0x630], 7
14003d3c7:  mov    ecx, 0x40
14003d3cc:  mov    qword ptr [rbp + 0x48], r15
14003d3d0:  movups xmmword ptr [rbp + 0xa8], xmm0
14003d3d7:  mov    word ptr [rbp + 0x618], si
14003d3de:  mov    qword ptr [rbp + 0xb8], rsi
14003d3e5:  mov    qword ptr [rbp + 0xc0], rsi
14003d3ec:  call   0x1400723e0
14003d3f1:  movups xmm0, xmmword ptr [rip + 0xa780b8]
14003d3f8:  mov    qword ptr [rbp + 0xa8], rax
14003d3ff:  lea    rdx, [rbp + 0x6d8]
14003d406:  movups xmm1, xmmword ptr [rip + 0xa780b3]
14003d40d:  mov    qword ptr [rbp + 0xb8], 0x37
14003d418:  mov    rcx, r15
14003d41b:  mov    qword ptr [rbp + 0xc0], 0x3f
14003d426:  xorps  xmm9, xmm9
14003d42a:  movups xmmword ptr [rax], xmm0
14003d42d:  movups xmm0, xmmword ptr [rip + 0xa7809c]
14003d434:  movups xmmword ptr [rax + 0x10], xmm1
14003d438:  movsd  xmm1, qword ptr [rip + 0xa7809f]
14003d440:  movups xmmword ptr [rax + 0x20], xmm0
14003d444:  movsd  qword ptr [rax + 0x2f], xmm1
14003d449:  mov    byte ptr [rax + 0x37], sil
14003d44d:  mov    rax, qword ptr [rbp + 0xa8]
14003d454:  mov    qword ptr [rbp + 0x6d8], rax
14003d45b:  mov    rax, qword ptr [rbp + 0xb0]
14003d462:  mov    qword ptr [rbp + 0x6e0], rax
14003d469:  mov    rax, qword ptr [rbp + 0xb8]
14003d470:  mov    qword ptr [rbp + 0x6e8], rax
14003d477:  mov    rax, qword ptr [rbp + 0xc0]
14003d47e:  mov    qword ptr [rbp + 0x6f0], rax
14003d485:  movss  dword ptr [rbp + 0xcc], xmm9
14003d48e:  movss  dword ptr [rbp + 0x6fc], xmm9
14003d497:  mov    byte ptr [rbp + 0xc8], sil
14003d49e:  mov    qword ptr [rbp + 0xb8], rsi
14003d4a5:  mov    qword ptr [rbp + 0xc0], 0xf
14003d4b0:  mov    byte ptr [rbp + 0xa8], sil
14003d4b7:  mov    byte ptr [rbp + 0x6f8], sil
14003d4be:  call   0x1400472f0
14003d4c3:  mov    rdx, qword ptr [rbp + 0xc0]
14003d4ca:  cmp    rdx, 0xf
14003d4ce:  jbe    0x14003d504
14003d4d0:  mov    rcx, qword ptr [rbp + 0xa8]
14003d4d7:  inc    rdx
14003d4da:  cmp    rdx, 0x1000
14003d4e1:  jb     0x14003d4ff
14003d4e3:  mov    rax, qword ptr [rcx - 8]
14003d4e7:  sub    rcx, rax
14003d4ea:  sub    rcx, 8
14003d4ee:  cmp    rcx, 0x1f
14003d4f2:  ja     0x14004215d
14003d4f8:  add    rdx, 0x27
14003d4fc:  mov    rcx, rax
14003d4ff:  call   0x14052e6f0
14003d504:  lea    rcx, [rbp + 0x58]
14003d508:  mov    qword ptr [rbp + 0xb8], rsi
14003d50f:  mov    qword ptr [rbp + 0xc0], 0xf
14003d51a:  mov    byte ptr [rbp + 0xa8], sil
14003d521:  call   0x14000ea80
14003d526:  test   al, al
14003d528:  je     0x140042288
14003d52e:  lea    rcx, [rbp + 0x990]
14003d535:  call   0x14000ea80
14003d53a:  test   al, al
14003d53c:  je     0x140042288
14003d542:  lea    rdx, [rbp + 0x2c0]
14003d549:  lea    rcx, [rbp + 0xbe0]
14003d550:  call   0x140071c40
14003d555:  mov    r8d, 3
14003d55b:  lea    rdx, [rbp + 0xbe0]
14003d562:  lea    rcx, [rbp + 0x14e0]
14003d569:  call   0x14006db80
14003d56e:  lea    rcx, [rbp + 0xbe0]
14003d575:  cmp    dword ptr [rax], 1
14003d578:  jbe    0x14003d6ad
14003d57e:  call   0x140071e80
14003d583:  lea    rcx, [rbp + 0x2c0]
14003d58a:  call   0x14000ea30
14003d58f:  test   al, al
14003d591:  jne    0x14003d6b2
14003d597:  xorps  xmm0, xmm0
14003d59a:  mov    qword ptr [rbp + 0x158], rsi
14003d5a1:  mov    ecx, 0x40
14003d5a6:  mov    qword ptr [rbp + 0x160], rsi
14003d5ad:  movups xmmword ptr [rbp + 0x148], xmm0
14003d5b4:  call   0x1400723e0
14003d5b9:  movups xmm0, xmmword ptr [rip + 0xa78028]
14003d5c0:  mov    qword ptr [rbp + 0x148], rax
14003d5c7:  lea    rdx, [rbp + 0xb88]
14003d5ce:  movups xmm1, xmmword ptr [rip + 0xa78023]
14003d5d5:  mov    rcx, r15
14003d5d8:  mov    qword ptr [rbp + 0x158], 0x3e
14003d5e3:  movss  xmm2, dword ptr [rip + 0xab2d89]
14003d5eb:  mov    qword ptr [rbp + 0x160], 0x3f
14003d5f6:  movups xmmword ptr [rax], xmm0
14003d5f9:  movups xmm0, xmmword ptr [rip + 0xa78008]
14003d600:  movups xmmword ptr [rax + 0x10], xmm1
14003d604:  movups xmm1, xmmword ptr [rip + 0xa7800b]
14003d60b:  movups xmmword ptr [rax + 0x20], xmm0
14003d60f:  movups xmmword ptr [rax + 0x2e], xmm1
14003d613:  mov    byte ptr [rax + 0x3e], sil
14003d617:  movups xmm1, xmmword ptr [rbp + 0x158]
14003d61e:  mov    byte ptr [rbp + 0x168], 1
14003d625:  movups xmm0, xmmword ptr [rbp + 0x148]
14003d62c:  mov    qword ptr [rbp + 0x158], rsi
14003d633:  movups xmmword ptr [rbp + 0xb98], xmm1
14003d63a:  mov    qword ptr [rbp + 0x160], 0xf
14003d645:  movups xmmword ptr [rbp + 0xb88], xmm0
14003d64c:  mov    byte ptr [rbp + 0x148], sil
14003d653:  movss  dword ptr [rbp + 0x16c], xmm2
14003d65b:  movss  dword ptr [rbp + 0xbac], xmm2
14003d663:  mov    byte ptr [rbp + 0xba8], 1
14003d66a:  call   0x1400472f0
14003d66f:  mov    r8, qword ptr [rbp + 0x160]
14003d676:  cmp    r8, 0xf
14003d67a:  jbe    0x14003d68f
14003d67c:  mov    rdx, qword ptr [rbp + 0x148]
14003d683:  lea    rcx, [rbp + 0x148]
14003d68a:  call   0x140072220
14003d68f:  mov    qword ptr [rbp + 0x158], rsi
14003d696:  mov    qword ptr [rbp + 0x160], 0xf
14003d6a1:  mov    byte ptr [rbp + 0x148], sil
14003d6a8:  jmp    0x140042394
14003d6ad:  call   0x140071e80
14003d6b2:  lea    rdx, [rbp + 0x2c0]
14003d6b9:  lea    rcx, [rbp + 0x1040]
14003d6c0:  call   0x140071c40
14003d6c5:  lea    rcx, [rbp + 0x1040]
14003d6cc:  call   0x14000e180
14003d6d1:  mov    rcx, rax
14003d6d4:  call   0x14000eb20
14003d6d9:  lea    rcx, [rbp + 0x1040]
14003d6e0:  movzx  ebx, al
14003d6e3:  call   0x140071e80
14003d6e8:  test   bl, bl
14003d6ea:  jne    0x14003d814
14003d6f0:  xorps  xmm0, xmm0
14003d6f3:  mov    qword ptr [rbp + 0xe0], rsi
14003d6fa:  mov    ecx, 0x50
14003d6ff:  mov    qword ptr [rbp + 0xe8], rsi
14003d706:  movups xmmword ptr [rbp + 0xd0], xmm0
14003d70d:  call   0x1400723e0
14003d712:  movaps xmm0, xmmword ptr [rip + 0xa77f17]
14003d719:  lea    rdx, [rbp + 0xbb8]
14003d720:  movaps xmm1, xmmword ptr [rip + 0xa77f19]
14003d727:  mov    rcx, rax
14003d72a:  movss  xmm2, dword ptr [rip + 0xab2c42]
14003d732:  mov    qword ptr [rbp + 0xd0], rax
14003d739:  mov    qword ptr [rbp + 0xe0], 0x42
14003d744:  mov    qword ptr [rbp + 0xe8], 0x4f
14003d74f:  movups xmmword ptr [rax], xmm0
14003d752:  movaps xmm0, xmmword ptr [rip + 0xa77ef7]
14003d759:  movups xmmword ptr [rax + 0x10], xmm1
14003d75d:  movaps xmm1, xmmword ptr [rip + 0xa77efc]
14003d764:  movups xmmword ptr [rax + 0x20], xmm0
14003d768:  movups xmmword ptr [rax + 0x30], xmm1
14003d76c:  movzx  eax, word ptr [rip + 0xa77efd]
14003d773:  mov    word ptr [rcx + 0x40], ax
14003d777:  mov    byte ptr [rcx + 0x42], sil
14003d77b:  mov    rcx, r15
14003d77e:  movups xmm1, xmmword ptr [rbp + 0xe0]
14003d785:  mov    byte ptr [rbp + 0xf0], 1
14003d78c:  movups xmm0, xmmword ptr [rbp + 0xd0]
14003d793:  mov    qword ptr [rbp + 0xe0], rsi
14003d79a:  movups xmmword ptr [rbp + 0xbc8], xmm1
14003d7a1:  mov    qword ptr [rbp + 0xe8], 0xf
14003d7ac:  movups xmmword ptr [rbp + 0xbb8], xmm0
14003d7b3:  mov    byte ptr [rbp + 0xd0], sil
14003d7ba:  movss  dword ptr [rbp + 0xf4], xmm2
14003d7c2:  movss  dword ptr [rbp + 0xbdc], xmm2
14003d7ca:  mov    byte ptr [rbp + 0xbd8], 1
14003d7d1:  call   0x1400472f0
14003d7d6:  mov    r8, qword ptr [rbp + 0xe8]
14003d7dd:  cmp    r8, 0xf
14003d7e1:  jbe    0x14003d7f6
14003d7e3:  mov    rdx, qword ptr [rbp + 0xd0]
14003d7ea:  lea    rcx, [rbp + 0xd0]
14003d7f1:  call   0x140072220
14003d7f6:  mov    qword ptr [rbp + 0xe0], rsi
14003d7fd:  mov    qword ptr [rbp + 0xe8], 0xf
14003d808:  mov    byte ptr [rbp + 0xd0], sil
14003d80f:  jmp    0x140042394
14003d814:  lea    rcx, [rbp + 0x58]
14003d818:  mov    ebx, esi
14003d81a:  call   0x14000eb20
14003d81f:  mov    r12d, 1
14003d825:  test   al, al
14003d827:  je     0x14003d869
14003d829:  lea    rcx, [rbp + 0x990]
14003d830:  call   0x14000eb20
14003d835:  test   al, al
14003d837:  je     0x14003d869
14003d839:  lea    rdx, [rbp + 0x28]
14003d83d:  mov    ebx, r12d
14003d840:  lea    rcx, [rbp + 0x1060]
14003d847:  call   0x140071c40
14003d84c:  lea    rcx, [rbp + 0x1060]
14003d853:  call   0x14000e180
14003d858:  mov    rcx, rax
14003d85b:  call   0x14000eb20
14003d860:  test   al, al
14003d862:  je     0x14003d869
14003d864:  xor    dil, dil
14003d867:  jmp    0x14003d875
14003d869:  movzx  edi, r12b
14003d86d:  test   ebx, ebx
14003d86f:  je     0x140042164
14003d875:  lea    rcx, [rbp + 0x1060]
14003d87c:  mov    r14d, esi
14003d87f:  call   0x140071e80
14003d884:  test   dil, dil
14003d887:  jne    0x140042164
14003d88d:  lea    rdx, [rbp + 0x1670]
14003d894:  lea    rcx, [rbp + 0x28]
14003d898:  call   0x14000dc90
14003d89d:  mov    rcx, rax
14003d8a0:  lea    rdx, [rbp + 0x1b0]
14003d8a7:  call   0x14000dab0
14003d8ac:  lea    rcx, [rbp + 0x1670]
14003d8b3:  call   0x140071e80
14003d8b8:  lea    rdx, [rbp + 0x28]
14003d8bc:  lea    rcx, [rbp + 0x1080]
14003d8c3:  call   0x140071c40
14003d8c8:  lea    rcx, [rbp + 0x1080]
14003d8cf:  call   0x14000e180
14003d8d4:  mov    rcx, rax
14003d8d7:  lea    rdx, [rip + 0xa7672a]   ; "project.json"
14003d8de:  call   0x14000d880
14003d8e3:  mov    rdx, rax
14003d8e6:  lea    rcx, [rbp + 0x280]
14003d8ed:  call   0x140071c40
14003d8f2:  lea    rcx, [rbp + 0x1080]
14003d8f9:  call   0x140071e80
14003d8fe:  cmp    qword ptr [rbp + 0x1c8], 0xf
14003d906:  lea    rcx, [rbp + 0x1b0]
14003d90d:  mov    r8, qword ptr [rbp + 0x1c0]
14003d914:  xorps  xmm0, xmm0
14003d917:  cmova  rcx, qword ptr [rbp + 0x1b0]
14003d91f:  mov    qword ptr [rbp + 0x2f0], rsi
14003d926:  mov    qword ptr [rbp + 0x2f8], 7
14003d931:  movups xmmword ptr [rbp + 0x2e0], xmm0
14003d938:  mov    word ptr [rbp + 0x2e0], si
14003d93f:  cmp    r8, 4
14003d943:  jne    0x14003d959
14003d945:  lea    rdx, [rip + 0xa76760]   ; ".mp4"
14003d94c:  call   0x1409d1dc0
14003d951:  test   eax, eax
14003d953:  je     0x14003e54b
14003d959:  lea    rdx, [rip + 0xa76754]   ; ".webm"
14003d960:  lea    rcx, [rbp + 0x1b0]
14003d967:  call   0x140042480
14003d96c:  test   al, al
14003d96e:  jne    0x14003e54b
14003d974:  lea    rdx, [rip + 0xa767d9]   ; ".json"
14003d97b:  mov    byte ptr [rbp + 0x2d98], sil
14003d982:  lea    rcx, [rbp + 0x1b0]
14003d989:  call   0x140042480
14003d98e:  test   al, al
14003d990:  je     0x14003ed0c
14003d996:  lea    rcx, [rbp + 0x280]
14003d99d:  call   0x14000d7a0
14003d9a2:  mov    rdx, rax
14003d9a5:  lea    rcx, [rbp + 0x10a0]
14003d9ac:  call   0x1400496b0
14003d9b1:  lea    rdx, [rbp + 0x10a0]
14003d9b8:  lea    rcx, [rbp + 0xcc0]
14003d9bf:  call   0x1404ce0d0
14003d9c4:  lea    rcx, [rbp + 0x10a0]
14003d9cb:  call   0x140071e80
14003d9d0:  cmp    byte ptr [rbp + 0xce9], sil
14003d9d7:  jne    0x14003da30
14003d9d9:  lea    rdx, [rip + 0xa77ca0]   ; "ui_browse_mobile_transcoder_error_failed_copying_wallpaper_files"
14003d9e0:  lea    rcx, [rsp + 0x30]
14003d9e5:  call   0x140071970
14003d9ea:  lea    rdx, [rsp + 0x30]
14003d9ef:  mov    byte ptr [rsp + 0x50], r12b
14003d9f4:  lea    rcx, [rbp + 0x2618]
14003d9fb:  mov    dword ptr [rsp + 0x54], 0xbf800000
14003da03:  call   0x140042520
14003da08:  mov    rdx, rax
14003da0b:  mov    rcx, r15
14003da0e:  call   0x1400472f0
14003da13:  lea    rcx, [rsp + 0x30]
14003da18:  call   0x140072260
14003da1d:  lea    rcx, [rbp + 0xcc0]
14003da24:  call   0x14001bdd0
14003da29:  xor    esi, esi
14003da2b:  jmp    0x1400420ea
14003da30:  mov    eax, dword ptr [rbp + 0xcec]
14003da36:  cmp    eax, r12d
14003da39:  jne    0x14003db90
14003da3f:  lea    rcx, [rbp + 0xcc0]
14003da46:  call   0x1404cfa20
14003da4b:  test   al, al
14003da4d:  je     0x14003db8a
14003da53:  lea    rcx, [rbp + 0xcc0]
14003da5a:  call   0x1404cfa80
14003da5f:  mov    rdx, rax
14003da62:  lea    rcx, [rbp + 0x1100]
14003da69:  call   0x140071970
14003da6e:  lea    rdx, [rbp + 0x1100]
14003da75:  lea    rcx, [rbp + 0x1690]
14003da7c:  call   0x14000d3a0
14003da81:  mov    rcx, rax
14003da84:  call   0x14000e180
14003da89:  mov    rcx, rax
14003da8c:  lea    rdx, [rip + 0xa76575]   ; "project.json"
14003da93:  call   0x14000d880
14003da98:  mov    rdx, rax
14003da9b:  lea    rcx, [rbp + 0x10e0]
14003daa2:  call   0x140071c40
14003daa7:  lea    rcx, [rbp + 0x1690]
14003daae:  call   0x140071e80
14003dab3:  lea    rdx, [rbp + 0x280]
14003daba:  lea    rcx, [rbp + 0x2e0]
14003dac1:  call   0x140019260
14003dac6:  lea    rdx, [rbp + 0x16b0]
14003dacd:  lea    rcx, [rbp + 0x10e0]
14003dad4:  call   0x14000dab0
14003dad9:  mov    rdx, rax
14003dadc:  lea    rcx, [rbp + 0x280]
14003dae3:  call   0x14000d620
14003dae8:  lea    rcx, [rbp + 0x16b0]
14003daef:  call   0x140072260
14003daf4:  lea    rcx, [rbp + 0x280]
14003dafb:  call   0x14000d7a0
14003db00:  mov    rdx, rax
14003db03:  lea    rcx, [rbp + 0x10c0]
14003db0a:  call   0x1400496b0
14003db0f:  lea    rdx, [rbp + 0x10c0]
14003db16:  lea    rcx, [rbp + 0x2900]
14003db1d:  call   0x1404ce0d0
14003db22:  mov    rdx, rax
14003db25:  lea    rcx, [rbp + 0xcc0]
14003db2c:  call   0x14002a3d0
14003db31:  lea    rcx, [rbp + 0x2900]
14003db38:  call   0x14001bdd0
14003db3d:  lea    rcx, [rbp + 0x10c0]
14003db44:  call   0x140071e80
14003db49:  lea    rcx, [rbp + 0xcc0]
14003db50:  call   0x1404d0400
14003db55:  mov    rdx, rax
14003db58:  lea    rcx, [rbp + 0x28]
14003db5c:  call   0x14000d4a0
14003db61:  lea    rcx, [rbp + 0x10e0]
14003db68:  call   0x140071e80
14003db6d:  lea    rcx, [rbp + 0x1100]
14003db74:  call   0x140072260
14003db79:  lea    rcx, [rbp + 0xcc0]
14003db80:  call   0x14001bdd0
14003db85:  jmp    0x14003ed0c
14003db8a:  mov    eax, dword ptr [rbp + 0xcec]
14003db90:  cmp    eax, r12d
14003db93:  lea    rcx, [rbp + 0x58]
14003db97:  lea    rdx, [rbp + 0x990]
14003db9e:  cmovne rdx, rcx
14003dba2:  lea    rcx, [rbp + 0x4d0]
14003dba9:  call   0x140071c40
14003dbae:  lea    rdx, [rbp + 0x4d0]
14003dbb5:  lea    rcx, [rbp + 0x1120]
14003dbbc:  call   0x140071c40
14003dbc1:  lea    rdx, [rbp + 0x16d0]
14003dbc8:  lea    rcx, [rbp + 0x280]
14003dbcf:  call   0x14000dc30
14003dbd4:  mov    rdx, rax
14003dbd7:  lea    rcx, [rbp + 0x1120]
14003dbde:  call   0x14000d850
14003dbe3:  mov    rdx, rax
14003dbe6:  lea    rcx, [rbp + 0x678]
14003dbed:  call   0x140071c40
14003dbf2:  lea    rcx, [rbp + 0x16d0]
14003dbf9:  call   0x140071e80
14003dbfe:  lea    rcx, [rbp + 0x1120]
14003dc05:  call   0x140071e80
14003dc0a:  xor    r8d, r8d
14003dc0d:  lea    rdx, [rbp + 0x678]
14003dc14:  lea    rcx, [rbp + 0x280]
14003dc1b:  call   0x14000e880
14003dc20:  test   al, al
14003dc22:  jne    0x14003dc6d
14003dc24:  lea    rdx, [rip + 0xa77a55]   ; "ui_browse_mobile_transcoder_error_failed_copying_wallpaper_files"
14003dc2b:  lea    rcx, [rsp + 0x30]
14003dc30:  call   0x140071970
14003dc35:  lea    rdx, [rsp + 0x30]
14003dc3a:  mov    byte ptr [rsp + 0x50], r12b
14003dc3f:  lea    rcx, [rbp + 0x2640]
14003dc46:  mov    dword ptr [rsp + 0x54], 0xbf800000
14003dc4e:  call   0x140042520
14003dc53:  mov    rdx, rax
14003dc56:  mov    rcx, r15
14003dc59:  call   0x1400472f0
14003dc5e:  lea    rcx, [rsp + 0x30]
14003dc63:  call   0x140072260
14003dc68:  jmp    0x14003e520
14003dc6d:  lea    rcx, [rbp + 0xcc0]
14003dc74:  call   0x1404d0450
14003dc79:  mov    rdx, rax
14003dc7c:  lea    rcx, [rbp + 0x4f0]
14003dc83:  call   0x14000d0f0
14003dc88:  lea    rcx, [rbp + 0x4f0]
14003dc8f:  call   0x14000e6a0
14003dc94:  test   al, al
14003dc96:  jne    0x14003de2b
14003dc9c:  lea    rcx, [rbp + 0x16f0]
14003dca3:  call   0x14000e700
14003dca8:  mov    rcx, rax
14003dcab:  call   0x14000e580
14003dcb0:  mov    rcx, rax
14003dcb3:  lea    rdx, [rip + 0xa77a0e]   ; "ui/dist/images/nopreview.jpg"
14003dcba:  call   0x14000d880
14003dcbf:  mov    rdx, rax
14003dcc2:  lea    rcx, [rbp + 0x638]
14003dcc9:  call   0x140071c40
14003dcce:  lea    rcx, [rbp + 0x16f0]
14003dcd5:  call   0x140071e80
14003dcda:  lea    rcx, [rbp + 0x638]
14003dce1:  call   0x14000e6a0
14003dce6:  test   al, al
14003dce8:  je     0x14003debe
14003dcee:  lea    rdx, [rbp + 0x638]
14003dcf5:  lea    rcx, [rbp + 0x4f0]
14003dcfc:  call   0x140019260
14003dd01:  lea    rcx, [rbp + 0x678]
14003dd08:  call   0x14000d7a0
14003dd0d:  mov    rdx, rax
14003dd10:  lea    rcx, [rbp + 0x1140]
14003dd17:  call   0x140014570
14003dd1c:  xor    edx, edx
14003dd1e:  lea    rcx, [rbp + 0xc00]
14003dd25:  call   0x140234fb0
14003dd2a:  xor    r8d, r8d
14003dd2d:  lea    rdx, [rbp + 0xc00]
14003dd34:  lea    rcx, [rbp + 0x1140]
14003dd3b:  call   0x14000fcb0
14003dd40:  test   al, al
14003dd42:  je     0x14003de07
14003dd48:  lea    rdx, [rbp + 0x1730]
14003dd4f:  lea    rcx, [rbp + 0x638]
14003dd56:  call   0x14000dc30
14003dd5b:  mov    rcx, rax
14003dd5e:  lea    rdx, [rbp + 0x1710]
14003dd65:  call   0x14000dab0
14003dd6a:  mov    rdx, rax
14003dd6d:  lea    rcx, [rbp]
14003dd71:  call   0x140235210
14003dd76:  lea    rdx, [rip + 0xa74493]   ; "preview"
14003dd7d:  lea    rcx, [rbp + 0x2090]
14003dd84:  call   0x140070ce0
14003dd89:  lea    rdx, [rbp - 0x80]
14003dd8d:  lea    rcx, [rbp + 0xc00]
14003dd94:  movups xmm0, xmmword ptr [rax]
14003dd97:  movaps xmmword ptr [rbp - 0x80], xmm0
14003dd9b:  call   0x1402372e0
14003dda0:  mov    rdx, rax
14003dda3:  lea    rcx, [rbp]
14003dda7:  call   0x140235460
14003ddac:  lea    rcx, [rbp]
14003ddb0:  call   0x140235370
14003ddb5:  lea    rcx, [rbp + 0x1710]
14003ddbc:  call   0x140072260
14003ddc1:  lea    rcx, [rbp + 0x1730]
14003ddc8:  call   0x140071e80
14003ddcd:  movzx  r8d, r12b
14003ddd1:  lea    rdx, [rbp + 0xc00]
14003ddd8:  lea    rcx, [rbp + 0x1750]
14003dddf:  call   0x14000efa0
14003dde4:  lea    rcx, [rbp + 0x678]
14003ddeb:  mov    rdx, rax
14003ddee:  call   0x14000d7a0
14003ddf3:  mov    rcx, rax
14003ddf6:  call   0x140014830
14003ddfb:  lea    rcx, [rbp + 0x1750]
14003de02:  call   0x140072260
14003de07:  lea    rcx, [rbp + 0xc00]
14003de0e:  call   0x140235370
14003de13:  lea    rcx, [rbp + 0x1140]
14003de1a:  call   0x140072260
14003de1f:  lea    rcx, [rbp + 0x638]
14003de26:  call   0x140071e80
14003de2b:  lea    rdx, [rbp + 0x4d0]
14003de32:  lea    rcx, [rbp + 0x1160]
14003de39:  call   0x140071c40
14003de3e:  lea    rdx, [rbp + 0x1770]
14003de45:  lea    rcx, [rbp + 0x4f0]
14003de4c:  call   0x14000dc30
14003de51:  mov    rdx, rax
14003de54:  lea    rcx, [rbp + 0x1160]
14003de5b:  call   0x14000d850
14003de60:  mov    rdx, rax
14003de63:  lea    rcx, [rbp + 0xc28]
14003de6a:  call   0x140071c40
14003de6f:  lea    rcx, [rbp + 0x1770]
14003de76:  call   0x140071e80
14003de7b:  lea    rcx, [rbp + 0x1160]
14003de82:  call   0x140071e80
14003de87:  xor    r8d, r8d
14003de8a:  lea    rdx, [rbp + 0xc28]
14003de91:  lea    rcx, [rbp + 0x4f0]
14003de98:  call   0x14000e880
14003de9d:  test   al, al
14003de9f:  jne    0x14003df0e
14003dea1:  lea    rdx, [rip + 0xa777d8]   ; "ui_browse_mobile_transcoder_error_failed_copying_wallpaper_files"
14003dea8:  lea    rcx, [rsp + 0x30]
14003dead:  call   0x140071970
14003deb2:  lea    rcx, [rbp + 0x2690]
14003deb9:  jmp    0x14003e4dc
14003debe:  lea    rdx, [rip + 0xa777bb]   ; "ui_browse_mobile_transcoder_error_failed_copying_wallpaper_files"
14003dec5:  lea    rcx, [rsp + 0x30]
14003deca:  call   0x140071970
14003decf:  lea    rdx, [rsp + 0x30]
14003ded4:  mov    byte ptr [rsp + 0x50], r12b
14003ded9:  lea    rcx, [rbp + 0x2668]
14003dee0:  mov    dword ptr [rsp + 0x54], 0xbf800000
14003dee8:  call   0x140042520
14003deed:  mov    rdx, rax
14003def0:  mov    rcx, r15
14003def3:  call   0x1400472f0
14003def8:  lea    rcx, [rsp + 0x30]
14003defd:  call   0x140072260
14003df02:  lea    rcx, [rbp + 0x638]
14003df09:  jmp    0x14003e50f
14003df0e:  mov    eax, dword ptr [rbp + 0xcec]
14003df14:  cmp    eax, r12d
14003df17:  jne    0x14003e355
14003df1d:  mov    r8d, r12d
14003df20:  lea    rdx, [rip + 0xa77801]   ; "engine.registerAsset[\s]*\([\s]*[\"\']([\s\w\d\.\\/]+)[\"\']"
14003df27:  lea    rcx, [rbp + 0xa28]
14003df2e:  call   0x140049590
14003df33:  lea    rcx, [rbp + 0x20c0]
14003df3a:  call   0x140042440
14003df3f:  lea    rcx, [rbp + 0x14f0]
14003df46:  call   0x140048820
14003df4b:  lea    rdx, [rbp + 0x28]
14003df4f:  lea    rcx, [rbp + 0x11a0]
14003df56:  call   0x140071c40
14003df5b:  lea    rcx, [rbp + 0x11a0]
14003df62:  call   0x14000e180
14003df67:  mov    rdx, rax
14003df6a:  lea    rcx, [rbp + 0x1180]
14003df71:  call   0x140071c40
14003df76:  lea    r9, [rsp + 0x60]
14003df7b:  lea    rdx, [rbp + 0x1180]
14003df82:  lea    rcx, [rbp + 0x348]
14003df89:  call   0x140068de0
14003df8e:  lea    rcx, [rbp + 0x1180]
14003df95:  call   0x140071e80
14003df9a:  lea    rcx, [rbp + 0x11a0]
14003dfa1:  call   0x140071e80
14003dfa6:  lea    rdx, [rbp + 0x348]
14003dfad:  lea    rcx, [rbp + 0x1530]
14003dfb4:  call   0x140017f80
14003dfb9:  mov    rdx, rax
14003dfbc:  lea    rcx, [rbp - 0x28]
14003dfc0:  call   0x140068c60
14003dfc5:  lea    rdx, [rbp + 0x348]
14003dfcc:  lea    rcx, [rbp + 0x1540]
14003dfd3:  call   0x140017f80
14003dfd8:  mov    rdx, rax
14003dfdb:  lea    rcx, [rbp - 0x80]
14003dfdf:  call   0x140068be0
14003dfe4:  mov    rax, qword ptr [rbp - 0x28]
14003dfe8:  mov    rdi, qword ptr [rbp - 0x80]
14003dfec:  cmp    rax, rdi
14003dfef:  je     0x14003e183
14003dff5:  nop    word ptr [rax + rax]
14003e000:  lea    rdx, [rax + 0x20]
14003e004:  lea    rcx, [rbp + 0x850]
14003e00b:  call   0x140071c40
14003e010:  lea    rcx, [rbp + 0x850]
14003e017:  call   0x14000e3b0
14003e01c:  test   al, al
14003e01e:  je     0x14003e161
14003e024:  lea    rdx, [rbp + 0x1790]
14003e02b:  lea    rcx, [rbp + 0x850]
14003e032:  call   0x14000dc90
14003e037:  mov    rcx, rax
14003e03a:  lea    rdx, [rbp - 0x70]
14003e03e:  call   0x14000dab0
14003e043:  lea    rcx, [rbp + 0x1790]
14003e04a:  call   0x140071e80
14003e04f:  lea    rdx, [rip + 0xa760fe]   ; ".json"
14003e056:  lea    rcx, [rbp - 0x70]
14003e05a:  call   0x140042480
14003e05f:  test   al, al
14003e061:  je     0x14003e158
14003e067:  lea    rcx, [rbp + 0x850]
14003e06e:  call   0x14000d7a0
14003e073:  mov    rdx, rax
14003e076:  lea    rcx, [rbp + 0x830]
14003e07d:  call   0x140014570
14003e082:  lea    rdx, [rbp + 0x2da0]
14003e089:  lea    rcx, [rbp + 0x830]
14003e090:  call   0x140049920
14003e095:  lea    rdx, [rbp + 0x340]
14003e09c:  lea    rcx, [rbp + 0x830]
14003e0a3:  call   0x1400498f0
14003e0a8:  mov    rcx, qword ptr [rbp + 0x2da0]
14003e0af:  lea    r9, [rbp + 0xa28]
14003e0b6:  lea    r8, [rbp + 0x20c0]
14003e0bd:  mov    rdx, qword ptr [rax]
14003e0c0:  call   0x140045910
14003e0c5:  test   al, al
14003e0c7:  je     0x14003e14c
14003e0cd:  nop    dword ptr [rax]
14003e0d0:  mov    rbx, qword ptr [rbp + 0x2100]
14003e0d7:  lea    rcx, [rbp + 0x20c0]
14003e0de:  mov    rdx, r12
14003e0e1:  mov    qword ptr [rbp + 0x2da0], rbx
14003e0e8:  call   0x140049520
14003e0ed:  mov    rcx, rax
14003e0f0:  lea    rdx, [rbp + 0x17b0]
14003e0f7:  call   0x1400495f0
14003e0fc:  mov    r8, rax
14003e0ff:  lea    rdx, [rsp + 0x60]
14003e104:  lea    rcx, [rbp + 0x14f0]
14003e10b:  call   0x140046420
14003e110:  lea    rcx, [rbp + 0x17b0]
14003e117:  call   0x140072260
14003e11c:  lea    rdx, [rbp + 0x340]
14003e123:  lea    rcx, [rbp + 0x830]
14003e12a:  call   0x1400498f0
14003e12f:  lea    r9, [rbp + 0xa28]
14003e136:  mov    rcx, rbx
14003e139:  lea    r8, [rbp + 0x20c0]
14003e140:  mov    rdx, qword ptr [rax]
14003e143:  call   0x140045910
14003e148:  test   al, al
14003e14a:  jne    0x14003e0d0
14003e14c:  lea    rcx, [rbp + 0x830]
14003e153:  call   0x140072260
14003e158:  lea    rcx, [rbp - 0x70]
14003e15c:  call   0x140072260
14003e161:  lea    rcx, [rbp + 0x850]
14003e168:  call   0x140071e80
14003e16d:  lea    rcx, [rbp - 0x28]
14003e171:  call   0x140068710
14003e176:  mov    rax, qword ptr [rbp - 0x28]
14003e17a:  cmp    rax, rdi
14003e17d:  jne    0x14003e000
14003e183:  lea    rcx, [rbp - 0x80]
14003e187:  call   0x140068c90
14003e18c:  lea    rcx, [rbp - 0x28]
14003e190:  call   0x140068c90
14003e195:  lea    rcx, [rbp + 0x348]
14003e19c:  call   0x140068c90
14003e1a1:  lea    rdx, [rbp + 0x4d0]
14003e1a8:  lea    rcx, [rbp + 0x11c0]
14003e1af:  call   0x140071c40
14003e1b4:  lea    rdx, [rip + 0xa775ad]   ; "scene.pkg"
14003e1bb:  lea    rcx, [rbp + 0x11c0]
14003e1c2:  call   0x14000d880
14003e1c7:  mov    rdx, rax
14003e1ca:  lea    rcx, [rbp + 0x870]
14003e1d1:  call   0x140071c40
14003e1d6:  lea    rcx, [rbp + 0x11c0]
14003e1dd:  call   0x140071e80
14003e1e2:  lea    rcx, [rbp + 0x658]
14003e1e9:  call   0x14006fd80
14003e1ee:  lea    r8, [rbp + 0x658]
14003e1f5:  lea    rdx, [rbp + 0x14f0]
14003e1fc:  lea    rcx, [rbp + 0xcc0]
14003e203:  call   0x140209ba0
14003e208:  test   al, al
14003e20a:  jne    0x14003e226
14003e20c:  lea    rdx, [rip + 0xa7746d]   ; "ui_browse_mobile_transcoder_error_failed_copying_wallpaper_files"
14003e213:  lea    rcx, [rsp + 0x30]
14003e218:  call   0x140071970
14003e21d:  lea    rcx, [rbp + 0x2730]
14003e224:  jmp    0x14003e275
14003e226:  lea    rdx, [rbp + 0x658]
14003e22d:  lea    rcx, [rbp - 0x70]
14003e231:  call   0x14000d3a0
14003e236:  xor    r8d, r8d
14003e239:  lea    rdx, [rbp + 0x870]
14003e240:  lea    rcx, [rbp - 0x70]
14003e244:  call   0x14000e880
14003e249:  lea    rcx, [rbp - 0x70]
14003e24d:  movzx  ebx, al
14003e250:  call   0x140071e80
14003e255:  test   bl, bl
14003e257:  jne    0x14003e2e2
14003e25d:  lea    rdx, [rip + 0xa7741c]   ; "ui_browse_mobile_transcoder_error_failed_copying_wallpaper_files"
14003e264:  lea    rcx, [rsp + 0x30]
14003e269:  call   0x140071970
14003e26e:  lea    rcx, [rbp + 0x25f0]
14003e275:  lea    rdx, [rsp + 0x30]
14003e27a:  mov    byte ptr [rsp + 0x50], r12b
14003e27f:  mov    dword ptr [rsp + 0x54], 0xbf800000
14003e287:  call   0x140042520
14003e28c:  mov    rdx, rax
14003e28f:  mov    rcx, r15
14003e292:  call   0x1400472f0
14003e297:  lea    rcx, [rsp + 0x30]
14003e29c:  call   0x140072260
14003e2a1:  lea    rcx, [rbp + 0x658]
14003e2a8:  call   0x140072260
14003e2ad:  lea    rcx, [rbp + 0x870]
14003e2b4:  call   0x140071e80
14003e2b9:  lea    rcx, [rbp + 0x14f0]
14003e2c0:  call   0x140018300
14003e2c5:  lea    rcx, [rbp + 0x20d0]
14003e2cc:  call   0x140045bd0
14003e2d1:  lea    rcx, [rbp + 0xa28]
14003e2d8:  call   0x140049550
14003e2dd:  jmp    0x14003e508
14003e2e2:  lea    rdx, [rbp + 0x658]
14003e2e9:  lea    rcx, [rbp - 0x70]
14003e2ed:  call   0x14000d3a0
14003e2f2:  lea    rcx, [rbp - 0x70]
14003e2f6:  call   0x14000ea30
14003e2fb:  lea    rcx, [rbp - 0x70]
14003e2ff:  call   0x140071e80
14003e304:  lea    rdx, [rbp + 0x870]
14003e30b:  lea    rcx, [rbp + 0x28]
14003e30f:  call   0x140019260
14003e314:  lea    rcx, [rbp + 0x658]
14003e31b:  call   0x140072260
14003e320:  lea    rcx, [rbp + 0x870]
14003e327:  call   0x140071e80
14003e32c:  lea    rcx, [rbp + 0x14f0]
14003e333:  call   0x140018300
14003e338:  lea    rcx, [rbp + 0x20d0]
14003e33f:  call   0x140045bd0
14003e344:  lea    rcx, [rbp + 0xa28]
14003e34b:  call   0x140049550
14003e350:  jmp    0x14003e483
14003e355:  cmp    eax, 4
14003e358:  jne    0x14003e4c4
14003e35e:  lea    rcx, [rbp + 0xcc0]
14003e365:  call   0x1404d0400
14003e36a:  mov    rdx, rax
14003e36d:  lea    rcx, [rbp + 0x8b0]
14003e374:  call   0x14000d0f0
14003e379:  lea    rdx, [rbp + 0x4d0]
14003e380:  lea    rcx, [rbp + 0x11e0]
14003e387:  call   0x140071c40
14003e38c:  lea    rdx, [rbp + 0x17d0]
14003e393:  lea    rcx, [rbp + 0x8b0]
14003e39a:  call   0x14000dc30
14003e39f:  mov    rdx, rax
14003e3a2:  lea    rcx, [rbp + 0x11e0]
14003e3a9:  call   0x14000d850
14003e3ae:  mov    rdx, rax
14003e3b1:  lea    rcx, [rbp + 0x890]
14003e3b8:  call   0x140071c40
14003e3bd:  lea    rcx, [rbp + 0x17d0]
14003e3c4:  call   0x140071e80
14003e3c9:  lea    rcx, [rbp + 0x11e0]
14003e3d0:  call   0x140071e80
14003e3d5:  xor    r8d, r8d
14003e3d8:  lea    rdx, [rbp + 0x890]
14003e3df:  lea    rcx, [rbp + 0x8b0]
14003e3e6:  call   0x14000e880
14003e3eb:  test   al, al
14003e3ed:  jne    0x14003e450
14003e3ef:  lea    rdx, [rip + 0xa7728a]   ; "ui_browse_mobile_transcoder_error_failed_copying_wallpaper_files"
14003e3f6:  lea    rcx, [rsp + 0x30]
14003e3fb:  call   0x140071970
14003e400:  lea    rdx, [rsp + 0x30]
14003e405:  mov    byte ptr [rsp + 0x50], r12b
14003e40a:  lea    rcx, [rbp + 0x26b8]
14003e411:  mov    dword ptr [rsp + 0x54], 0xbf800000
14003e419:  call   0x140042520
14003e41e:  mov    rdx, rax
14003e421:  mov    rcx, r15
14003e424:  call   0x1400472f0
14003e429:  lea    rcx, [rsp + 0x30]
14003e42e:  call   0x140072260
14003e433:  lea    rcx, [rbp + 0x890]
14003e43a:  call   0x140071e80
14003e43f:  lea    rcx, [rbp + 0x8b0]
14003e446:  call   0x140071e80
14003e44b:  jmp    0x14003e508
14003e450:  lea    rdx, [rbp + 0x890]
14003e457:  mov    byte ptr [rbp + 0x2d98], r12b
14003e45e:  lea    rcx, [rbp + 0x28]
14003e462:  movzx  esi, r12b
14003e466:  call   0x140019260
14003e46b:  lea    rcx, [rbp + 0x890]
14003e472:  call   0x140071e80
14003e477:  lea    rcx, [rbp + 0x8b0]
14003e47e:  call   0x140071e80
14003e483:  lea    rcx, [rbp + 0xc28]
14003e48a:  call   0x140071e80
14003e48f:  lea    rcx, [rbp + 0x4f0]
14003e496:  call   0x140071e80
14003e49b:  lea    rcx, [rbp + 0x678]
14003e4a2:  call   0x140071e80
14003e4a7:  lea    rcx, [rbp + 0x4d0]
14003e4ae:  call   0x140071e80
14003e4b3:  lea    rcx, [rbp + 0xcc0]
14003e4ba:  call   0x14001bdd0
14003e4bf:  jmp    0x14003ed0c
14003e4c4:  lea    rdx, [rip + 0xa771b5]   ; "ui_browse_mobile_transcoder_error_failed_copying_wallpaper_files"
14003e4cb:  lea    rcx, [rsp + 0x30]
14003e4d0:  call   0x140071970
14003e4d5:  lea    rcx, [rbp + 0x26e0]
14003e4dc:  lea    rdx, [rsp + 0x30]
14003e4e1:  mov    byte ptr [rsp + 0x50], r12b
14003e4e6:  mov    dword ptr [rsp + 0x54], 0xbf800000
14003e4ee:  call   0x140042520
14003e4f3:  mov    rdx, rax
14003e4f6:  mov    rcx, r15
14003e4f9:  call   0x1400472f0
14003e4fe:  lea    rcx, [rsp + 0x30]
14003e503:  call   0x140072260
14003e508:  lea    rcx, [rbp + 0xc28]
14003e50f:  call   0x140071e80
14003e514:  lea    rcx, [rbp + 0x4f0]
14003e51b:  call   0x140071e80
14003e520:  lea    rcx, [rbp + 0x678]
14003e527:  call   0x140071e80
14003e52c:  lea    rcx, [rbp + 0x4d0]
14003e533:  call   0x140071e80
14003e538:  lea    rcx, [rbp + 0xcc0]
14003e53f:  call   0x14001bdd0
14003e544:  xor    esi, esi
14003e546:  jmp    0x1400420ea
14003e54b:  lea    rdx, [rbp + 0x58]
14003e54f:  lea    rcx, [rbp + 0x1200]
14003e556:  call   0x140071c40
14003e55b:  lea    rdx, [rbp + 0x17f0]
14003e562:  lea    rcx, [rbp + 0x28]
14003e566:  call   0x1400711f0
14003e56b:  mov    qword ptr [rbp + 0x4c0], rsi
14003e572:  lea    rcx, [rbp + 0x17f0]
14003e579:  mov    qword ptr [rbp + 0x4c8], rsi
14003e580:  xorps  xmm0, xmm0
14003e583:  movups xmmword ptr [rbp + 0x4b0], xmm0
14003e58a:  movups xmm0, xmmword ptr [rax]
14003e58d:  movups xmmword ptr [rbp + 0x4b0], xmm0
14003e594:  movups xmm1, xmmword ptr [rax + 0x10]
14003e598:  movups xmmword ptr [rbp + 0x4c0], xmm1
14003e59f:  mov    qword ptr [rax + 0x10], rsi
14003e5a3:  mov    qword ptr [rax + 0x18], 7
14003e5ab:  mov    word ptr [rax], si
14003e5ae:  call   0x140071e80
14003e5b3:  lea    rdx, [rbp + 0x4b0]
14003e5ba:  lea    rcx, [rbp + 0x1200]
14003e5c1:  call   0x14000d850
14003e5c6:  mov    rdx, rax
14003e5c9:  lea    rcx, [rbp + 0x510]
14003e5d0:  call   0x140071c40
14003e5d5:  lea    rcx, [rbp + 0x4b0]
14003e5dc:  call   0x140071e80
14003e5e1:  lea    rcx, [rbp + 0x1200]
14003e5e8:  call   0x140071e80
14003e5ed:  xor    r8d, r8d
14003e5f0:  lea    rdx, [rbp + 0x510]
14003e5f7:  lea    rcx, [rbp + 0x28]
14003e5fb:  call   0x14000e880
14003e600:  test   al, al
14003e602:  jne    0x14003e659
14003e604:  lea    rdx, [rip + 0xa77075]   ; "ui_browse_mobile_transcoder_error_failed_copying_wallpaper_files"
14003e60b:  lea    rcx, [rsp + 0x30]
14003e610:  call   0x140071970
14003e615:  lea    rdx, [rsp + 0x30]
14003e61a:  mov    byte ptr [rsp + 0x50], r12b
14003e61f:  lea    rcx, [rbp + 0x2708]
14003e626:  mov    dword ptr [rsp + 0x54], 0xbf800000
14003e62e:  call   0x140042520
14003e633:  mov    rdx, rax
14003e636:  mov    rcx, r15
14003e639:  call   0x1400472f0
14003e63e:  lea    rcx, [rsp + 0x30]
14003e643:  call   0x140072260
14003e648:  lea    rcx, [rbp + 0x510]
14003e64f:  call   0x140071e80
14003e654:  jmp    0x1400420ea
14003e659:  xor    edx, edx
14003e65b:  lea    rcx, [rbp + 0x9d0]
14003e662:  call   0x140234fb0
14003e667:  lea    rdx, [rbp + 0x1810]
14003e66e:  xor    bl, bl
14003e670:  lea    rcx, [rbp + 0x510]
14003e677:  call   0x14000df80
14003e67c:  mov    rcx, rax
14003e67f:  lea    rdx, [rsp + 0x60]
14003e684:  call   0x14000dab0
14003e689:  lea    rcx, [rbp + 0x1810]
14003e690:  call   0x140071e80
14003e695:  lea    rcx, [rbp + 0x280]
14003e69c:  call   0x14000e6a0
14003e6a1:  test   al, al
14003e6a3:  je     0x14003e6e5
14003e6a5:  lea    rcx, [rbp + 0x280]
14003e6ac:  call   0x14000d7a0
14003e6b1:  mov    rdx, rax
14003e6b4:  lea    rcx, [rbp + 0x1220]
14003e6bb:  call   0x140014570
14003e6c0:  xor    r8d, r8d
14003e6c3:  lea    rdx, [rbp + 0x9d0]
14003e6ca:  lea    rcx, [rbp + 0x1220]
14003e6d1:  call   0x14000fcb0
14003e6d6:  lea    rcx, [rbp + 0x1220]
14003e6dd:  movzx  ebx, al
14003e6e0:  call   0x140072260
14003e6e5:  lea    rcx, [rbp + 0x240]
14003e6ec:  call   0x14001c1f0
14003e6f1:  test   bl, bl
14003e6f3:  je     0x14003e795
14003e6f9:  lea    rdx, [rip + 0xa73b10]   ; "preview"
14003e700:  lea    rcx, [rbp + 0x1550]
14003e707:  call   0x140070ce0
14003e70c:  lea    rdx, [rbp - 0x80]
14003e710:  lea    rcx, [rbp + 0x9d0]
14003e717:  movups xmm0, xmmword ptr [rax]
14003e71a:  movaps xmmword ptr [rbp - 0x80], xmm0
14003e71e:  call   0x1402372e0
14003e723:  mov    rdi, rax
14003e726:  cmp    byte ptr [rax + 8], 4
14003e72a:  jne    0x14003e795
14003e72c:  lea    rdx, [rbp + 0x28]
14003e730:  lea    rcx, [rbp + 0x1240]
14003e737:  call   0x140071c40
14003e73c:  lea    rcx, [rbp + 0x1240]
14003e743:  call   0x14000e180
14003e748:  mov    rcx, rdi
14003e74b:  mov    r8, rax
14003e74e:  call   0x1402357b0
14003e753:  mov    rdx, rax
14003e756:  mov    rcx, r8
14003e759:  call   0x14000d880
14003e75e:  mov    rdx, rax
14003e761:  lea    rcx, [rbp + 0x240]
14003e768:  call   0x140019260
14003e76d:  lea    rcx, [rbp + 0x1240]
14003e774:  call   0x140071e80
14003e779:  lea    rcx, [rbp + 0x240]
14003e780:  call   0x14000e6a0
14003e785:  test   al, al
14003e787:  jne    0x14003e795
14003e789:  lea    rcx, [rbp + 0x240]
14003e790:  call   0x14000e2b0
14003e795:  cmp    qword ptr [rbp + 0x250], rsi
14003e79c:  jne    0x14003e809
14003e79e:  lea    rdx, [rbp + 0x28]
14003e7a2:  lea    rcx, [rbp + 0x1830]
14003e7a9:  call   0x140071c40
14003e7ae:  mov    r8d, 0x168
14003e7b4:  lea    rdx, [rbp + 0x240]
14003e7bb:  lea    rcx, [rbp + 0x1830]
14003e7c2:  call   0x140018a50
14003e7c7:  test   al, al
14003e7c9:  jne    0x14003e809
14003e7cb:  lea    rcx, [rbp + 0x1850]
14003e7d2:  call   0x14000e700
14003e7d7:  mov    rcx, rax
14003e7da:  call   0x14000e580
14003e7df:  mov    rcx, rax
14003e7e2:  lea    rdx, [rip + 0xa76edf]   ; "ui/dist/images/nopreview.jpg"
14003e7e9:  call   0x14000d880
14003e7ee:  mov    rdx, rax
14003e7f1:  lea    rcx, [rbp + 0x240]
14003e7f8:  call   0x140019260
14003e7fd:  lea    rcx, [rbp + 0x1850]
14003e804:  call   0x140071e80
14003e809:  lea    rcx, [rbp + 0x240]
14003e810:  call   0x14000e6a0
14003e815:  test   al, al
14003e817:  jne    0x14003e862
14003e819:  lea    rdx, [rip + 0xa76ec8]   ; "ui_browse_mobile_transcoder_error_failed_creating_thumbnail"
14003e820:  lea    rcx, [rsp + 0x30]
14003e825:  call   0x140071970
14003e82a:  lea    rdx, [rsp + 0x30]
14003e82f:  mov    byte ptr [rsp + 0x50], r12b
14003e834:  lea    rcx, [rbp + 0x2370]
14003e83b:  mov    dword ptr [rsp + 0x54], 0xbf800000
14003e843:  call   0x140042520
14003e848:  mov    rdx, rax
14003e84b:  mov    rcx, r15
14003e84e:  call   0x1400472f0
14003e853:  lea    rcx, [rsp + 0x30]
14003e858:  call   0x140072260
14003e85d:  jmp    0x14003ec6c
14003e862:  lea    rdx, [rbp + 0x58]
14003e866:  lea    rcx, [rbp + 0x1260]
14003e86d:  call   0x140071c40
14003e872:  lea    rdx, [rbp + 0x1870]
14003e879:  lea    rcx, [rbp + 0x240]
14003e880:  call   0x14000dc30
14003e885:  mov    rdx, rax
14003e888:  lea    rcx, [rbp + 0x1260]
14003e88f:  call   0x14000d850
14003e894:  mov    rdx, rax
14003e897:  lea    rcx, [rbp + 0x7d0]
14003e89e:  call   0x140071c40
14003e8a3:  lea    rcx, [rbp + 0x1870]
14003e8aa:  call   0x140071e80
14003e8af:  lea    rcx, [rbp + 0x1260]
14003e8b6:  call   0x140071e80
14003e8bb:  xor    r8d, r8d
14003e8be:  lea    rdx, [rbp + 0x7d0]
14003e8c5:  lea    rcx, [rbp + 0x240]
14003e8cc:  call   0x14000e880
14003e8d1:  test   al, al
14003e8d3:  jne    0x14003e91e
14003e8d5:  lea    rdx, [rip + 0xa76da4]   ; "ui_browse_mobile_transcoder_error_failed_copying_wallpaper_files"
14003e8dc:  lea    rcx, [rsp + 0x30]
14003e8e1:  call   0x140071970
14003e8e6:  lea    rdx, [rsp + 0x30]
14003e8eb:  mov    byte ptr [rsp + 0x50], r12b
14003e8f0:  lea    rcx, [rbp + 0x2398]
14003e8f7:  mov    dword ptr [rsp + 0x54], 0xbf800000
14003e8ff:  call   0x140042520
14003e904:  mov    rdx, rax
14003e907:  mov    rcx, r15
14003e90a:  call   0x1400472f0
14003e90f:  lea    rcx, [rsp + 0x30]
14003e914:  call   0x140072260
14003e919:  jmp    0x14003ec60
14003e91e:  test   bl, bl
14003e920:  je     0x14003e9cd
14003e926:  lea    rdx, [rip + 0xa745d7]   ; "title"
14003e92d:  lea    rcx, [rbp + 0x1560]
14003e934:  call   0x140070ce0
14003e939:  lea    rdx, [rbp - 0x80]
14003e93d:  lea    rcx, [rbp + 0x9d0]
14003e944:  movups xmm0, xmmword ptr [rax]
14003e947:  movaps xmmword ptr [rbp - 0x80], xmm0
14003e94b:  call   0x1402372e0
14003e950:  cmp    byte ptr [rax + 8], 4
14003e954:  jne    0x14003e9cd
14003e956:  lea    rdx, [rbp + 0x1890]
14003e95d:  mov    rcx, rax
14003e960:  call   0x1402357d0
14003e965:  mov    rdx, rax
14003e968:  lea    rcx, [rbp + 0x8d0]
14003e96f:  call   0x14000c300
14003e974:  lea    rcx, [rbp + 0x1890]
14003e97b:  call   0x140072260
14003e980:  lea    rcx, [rbp + 0x8d0]
14003e987:  call   0x14000ce80
14003e98c:  cmp    qword ptr [rbp + 0x8e0], rsi
14003e993:  je     0x14003e9c1
14003e995:  lea    rdx, [rbp + 0x8d0]
14003e99c:  lea    rcx, [rbp + 0x18b0]
14003e9a3:  call   0x14000c210
14003e9a8:  mov    rdx, rax
14003e9ab:  lea    rcx, [rsp + 0x60]
14003e9b0:  call   0x140049b10
14003e9b5:  lea    rcx, [rbp + 0x18b0]
14003e9bc:  call   0x140072260
14003e9c1:  lea    rcx, [rbp + 0x8d0]
14003e9c8:  call   0x140071e80
14003e9cd:  lea    rdx, [rbp + 0x58]
14003e9d1:  lea    rcx, [rbp + 0x1280]
14003e9d8:  call   0x140071c40
14003e9dd:  lea    rdx, [rip + 0xa75624]   ; "project.json"
14003e9e4:  lea    rcx, [rbp + 0x1280]
14003e9eb:  call   0x14000d880
14003e9f0:  mov    rdx, rax
14003e9f3:  lea    rcx, [rbp + 0xb48]
14003e9fa:  call   0x140071c40
14003e9ff:  lea    rcx, [rbp + 0x1280]
14003ea06:  call   0x140071e80
14003ea0b:  xor    edx, edx
14003ea0d:  lea    rcx, [rbp + 0x550]
14003ea14:  call   0x140234fb0
14003ea19:  lea    rdx, [rbp + 0x18f0]
14003ea20:  lea    rcx, [rbp + 0x510]
14003ea27:  call   0x14000dc30
14003ea2c:  mov    rcx, rax
14003ea2f:  lea    rdx, [rbp + 0x18d0]
14003ea36:  call   0x14000dab0
14003ea3b:  mov    rdx, rax
14003ea3e:  lea    rcx, [rbp]
14003ea42:  call   0x140235210
14003ea47:  lea    rdx, [rip + 0xa737b6]   ; "file"
14003ea4e:  lea    rcx, [rbp + 0x1570]
14003ea55:  call   0x140070ce0
14003ea5a:  lea    rdx, [rbp - 0x80]
14003ea5e:  lea    rcx, [rbp + 0x550]
14003ea65:  movups xmm0, xmmword ptr [rax]
14003ea68:  movaps xmmword ptr [rbp - 0x80], xmm0
14003ea6c:  call   0x1402372e0
14003ea71:  mov    rdx, rax
14003ea74:  lea    rcx, [rbp]
14003ea78:  call   0x140235460
14003ea7d:  lea    rcx, [rbp]
14003ea81:  call   0x140235370
14003ea86:  lea    rcx, [rbp + 0x18d0]
14003ea8d:  call   0x140072260
14003ea92:  lea    rcx, [rbp + 0x18f0]
14003ea99:  call   0x140071e80
14003ea9e:  lea    rdx, [rbp + 0x1930]
14003eaa5:  lea    rcx, [rbp + 0x7d0]
14003eaac:  call   0x14000dc30
14003eab1:  mov    rcx, rax
14003eab4:  lea    rdx, [rbp + 0x1910]
14003eabb:  call   0x14000dab0
14003eac0:  mov    rdx, rax
14003eac3:  lea    rcx, [rbp]
14003eac7:  call   0x140235210
14003eacc:  lea    rdx, [rip + 0xa7373d]   ; "preview"
14003ead3:  lea    rcx, [rbp + 0x1580]
14003eada:  call   0x140070ce0
14003eadf:  lea    rdx, [rbp - 0x80]
14003eae3:  lea    rcx, [rbp + 0x550]
14003eaea:  movups xmm0, xmmword ptr [rax]
14003eaed:  movaps xmmword ptr [rbp - 0x80], xmm0
14003eaf1:  call   0x1402372e0
14003eaf6:  mov    rdx, rax
14003eaf9:  lea    rcx, [rbp]
14003eafd:  call   0x140235460
14003eb02:  lea    rcx, [rbp]
14003eb06:  call   0x140235370
14003eb0b:  lea    rcx, [rbp + 0x1910]
14003eb12:  call   0x140072260
14003eb17:  lea    rcx, [rbp + 0x1930]
14003eb1e:  call   0x140071e80
14003eb23:  lea    rdx, [rsp + 0x60]
14003eb28:  lea    rcx, [rbp]
14003eb2c:  call   0x140235210
14003eb31:  lea    rdx, [rip + 0xa743cc]   ; "title"
14003eb38:  lea    rcx, [rbp + 0x1590]
14003eb3f:  call   0x140070ce0
14003eb44:  lea    rdx, [rbp - 0x80]
14003eb48:  lea    rcx, [rbp + 0x550]
14003eb4f:  movups xmm0, xmmword ptr [rax]
14003eb52:  movaps xmmword ptr [rbp - 0x80], xmm0
14003eb56:  call   0x1402372e0
14003eb5b:  mov    rdx, rax
14003eb5e:  lea    rcx, [rbp]
14003eb62:  call   0x140235460
14003eb67:  lea    rcx, [rbp]
14003eb6b:  call   0x140235370
14003eb70:  lea    rdx, [rip + 0xa75065]   ; "video"
14003eb77:  lea    rcx, [rbp]
14003eb7b:  call   0x1402351c0
14003eb80:  lea    rdx, [rip + 0xa75019]   ; "type"
14003eb87:  lea    rcx, [rbp + 0x15a0]
14003eb8e:  call   0x140070ce0
14003eb93:  lea    rdx, [rbp - 0x80]
14003eb97:  lea    rcx, [rbp + 0x550]
14003eb9e:  movups xmm0, xmmword ptr [rax]
14003eba1:  movaps xmmword ptr [rbp - 0x80], xmm0
14003eba5:  call   0x1402372e0
14003ebaa:  mov    rdx, rax
14003ebad:  lea    rcx, [rbp]
14003ebb1:  call   0x140235460
14003ebb6:  lea    rcx, [rbp]
14003ebba:  call   0x140235370
14003ebbf:  movzx  r8d, r12b
14003ebc3:  lea    rdx, [rbp + 0x550]
14003ebca:  lea    rcx, [rbp + 0x1950]
14003ebd1:  call   0x14000efa0
14003ebd6:  lea    rcx, [rbp + 0xb48]
14003ebdd:  mov    rdx, rax
14003ebe0:  call   0x14000d7a0
14003ebe5:  mov    rcx, rax
14003ebe8:  call   0x140014830
14003ebed:  lea    rcx, [rbp + 0x1950]
14003ebf4:  movzx  ebx, al
14003ebf7:  call   0x140072260
14003ebfc:  test   bl, bl
14003ebfe:  jne    0x14003ec9f
14003ec04:  lea    rdx, [rip + 0xa76a75]   ; "ui_browse_mobile_transcoder_error_failed_copying_wallpaper_files"
14003ec0b:  lea    rcx, [rsp + 0x30]
14003ec10:  call   0x140071970
14003ec15:  lea    rdx, [rsp + 0x30]
14003ec1a:  mov    byte ptr [rsp + 0x50], r12b
14003ec1f:  lea    rcx, [rbp + 0x23c0]
14003ec26:  mov    dword ptr [rsp + 0x54], 0xbf800000
14003ec2e:  call   0x140042520
14003ec33:  mov    rdx, rax
14003ec36:  mov    rcx, r15
14003ec39:  call   0x1400472f0
14003ec3e:  lea    rcx, [rsp + 0x30]
14003ec43:  call   0x140072260
14003ec48:  lea    rcx, [rbp + 0x550]
14003ec4f:  call   0x140235370
14003ec54:  lea    rcx, [rbp + 0xb48]
14003ec5b:  call   0x140071e80
14003ec60:  lea    rcx, [rbp + 0x7d0]
14003ec67:  call   0x140071e80
14003ec6c:  lea    rcx, [rbp + 0x240]
14003ec73:  call   0x140071e80
14003ec78:  lea    rcx, [rsp + 0x60]
14003ec7d:  call   0x140072260
14003ec82:  lea    rcx, [rbp + 0x9d0]
14003ec89:  call   0x140235370
14003ec8e:  lea    rcx, [rbp + 0x510]
14003ec95:  call   0x140071e80
14003ec9a:  jmp    0x1400420ea
14003ec9f:  lea    rdx, [rbp + 0x510]
14003eca6:  mov    byte ptr [rbp + 0x2d98], r12b
14003ecad:  lea    rcx, [rbp + 0x28]
14003ecb1:  movzx  esi, r12b
14003ecb5:  call   0x140019260
14003ecba:  lea    rcx, [rbp + 0x550]
14003ecc1:  call   0x140235370
14003ecc6:  lea    rcx, [rbp + 0xb48]
14003eccd:  call   0x140071e80
14003ecd2:  lea    rcx, [rbp + 0x7d0]
14003ecd9:  call   0x140071e80
14003ecde:  lea    rcx, [rbp + 0x240]
14003ece5:  call   0x140071e80
14003ecea:  lea    rcx, [rsp + 0x60]
14003ecef:  call   0x140072260
14003ecf4:  lea    rcx, [rbp + 0x9d0]
14003ecfb:  call   0x140235370
14003ed00:  lea    rcx, [rbp + 0x510]
14003ed07:  call   0x140071e80
14003ed0c:  lea    rdx, [rbp + 0x1990]
14003ed13:  lea    rcx, [rbp + 0x28]
14003ed17:  call   0x14000dc90
14003ed1c:  mov    rcx, rax
14003ed1f:  lea    rdx, [rbp + 0x1970]
14003ed26:  call   0x14000dab0
14003ed2b:  mov    rdx, rax
14003ed2e:  lea    rcx, [rbp + 0x1b0]
14003ed35:  call   0x140049b10
14003ed3a:  lea    rcx, [rbp + 0x1970]
14003ed41:  call   0x140072260
14003ed46:  lea    rcx, [rbp + 0x1990]
14003ed4d:  call   0x140071e80
14003ed52:  lea    rdx, [rip + 0xa75457]   ; ".pkg"
14003ed59:  lea    rcx, [rbp + 0x1b0]
14003ed60:  call   0x140042480
14003ed65:  movzx  edi, al
14003ed68:  test   al, al
14003ed6a:  je     0x14003eebb
14003ed70:  lea    rcx, [rbp + 0x2a00]
14003ed77:  call   0x140471810
14003ed7c:  lea    rcx, [rbp + 0x28]
14003ed80:  call   0x14000e6a0
14003ed85:  test   al, al
14003ed87:  je     0x14003edbc
14003ed89:  lea    rdx, [rbp + 0x19b0]
14003ed90:  or     r14d, 2
14003ed94:  lea    rcx, [rbp + 0x28]
14003ed98:  call   0x14000dab0
14003ed9d:  mov    rcx, rax
14003eda0:  call   0x1400498e0
14003eda5:  mov    rdx, rax
14003eda8:  lea    rcx, [rbp + 0x2a00]
14003edaf:  call   0x140474430
14003edb4:  test   eax, eax
14003edb6:  jne    0x14003edbc
14003edb8:  xor    bl, bl
14003edba:  jmp    0x14003edc0
14003edbc:  movzx  ebx, r12b
14003edc0:  test   r14b, 2
14003edc4:  je     0x14003edd6
14003edc6:  and    r14d, 0xfffffffd
14003edca:  lea    rcx, [rbp + 0x19b0]
14003edd1:  call   0x140072260
14003edd6:  test   bl, bl
14003edd8:  je     0x14003ee31
14003edda:  lea    rdx, [rip + 0xa76997]   ; "ui_browse_mobile_transcoder_error_failed_opening_pkg"
14003ede1:  lea    rcx, [rsp + 0x30]
14003ede6:  call   0x140071970
14003edeb:  lea    rdx, [rsp + 0x30]
14003edf0:  mov    byte ptr [rsp + 0x50], r12b
14003edf5:  lea    rcx, [rbp + 0x23e8]
14003edfc:  mov    dword ptr [rsp + 0x54], 0xbf800000
14003ee04:  call   0x140042520
14003ee09:  mov    rdx, rax
14003ee0c:  mov    rcx, r15
14003ee0f:  call   0x1400472f0
14003ee14:  lea    rcx, [rsp + 0x30]
14003ee19:  call   0x140072260
14003ee1e:  lea    rcx, [rbp + 0x2a00]
14003ee25:  call   0x14002a710
14003ee2a:  xor    esi, esi
14003ee2c:  jmp    0x1400420ea
14003ee31:  lea    rcx, [r13 + 0x1b0]
14003ee38:  mov    edx, 5
14003ee3d:  call   0x14004fab0
14003ee42:  test   al, al
14003ee44:  jne    0x14003ee1e
14003ee46:  lea    rdx, [rbp + 0x19d0]
14003ee4d:  lea    rcx, [rbp + 0x58]
14003ee51:  call   0x14000dab0
14003ee56:  mov    rcx, rax
14003ee59:  call   0x1400498e0
14003ee5e:  mov    rdx, rax
14003ee61:  lea    rcx, [rbp + 0x2a00]
14003ee68:  call   0x140474d00
14003ee6d:  lea    rcx, [rbp + 0x19d0]
14003ee74:  mov    ebx, eax
14003ee76:  call   0x140072260
14003ee7b:  cmp    ebx, r12d
14003ee7e:  jge    0x14003eeaf
14003ee80:  lea    rdx, [rip + 0xa76929]   ; "ui_browse_mobile_transcoder_error_failed_unpacking_files"
14003ee87:  lea    rcx, [rsp + 0x30]
14003ee8c:  call   0x140071970
14003ee91:  lea    rdx, [rsp + 0x30]
14003ee96:  mov    byte ptr [rsp + 0x50], r12b
14003ee9b:  lea    rcx, [rbp + 0x2410]
14003eea2:  mov    dword ptr [rsp + 0x54], 0xbf800000
14003eeaa:  jmp    0x14003ee04
14003eeaf:  lea    rcx, [rbp + 0x2a00]
14003eeb6:  call   0x14002a710
14003eebb:  lea    rdx, [rip + 0xa765ee]   ; "ui_browse_mobile_transcoder_progress_converting_project"
14003eec2:  lea    rcx, [rsp + 0x30]
14003eec7:  call   0x140071970
14003eecc:  lea    rdx, [rsp + 0x30]
14003eed1:  mov    byte ptr [rsp + 0x50], 0
14003eed6:  lea    rcx, [rbp + 0x2438]
14003eedd:  mov    dword ptr [rsp + 0x54], 0x3dcccccd
14003eee5:  call   0x140042520
14003eeea:  mov    rdx, rax
14003eeed:  mov    rcx, r15
14003eef0:  call   0x1400472f0
14003eef5:  lea    rcx, [rsp + 0x30]
14003eefa:  call   0x140072260
14003eeff:  xorps  xmm0, xmm0
14003ef02:  lea    rcx, [rbp + 0x2a0]
14003ef09:  xor    ebx, ebx
14003ef0b:  movdqu xmmword ptr [rbp - 0x18], xmm0
14003ef10:  mov    qword ptr [rbp - 8], rbx
14003ef14:  call   0x14006fd80
14003ef19:  movss  xmm11, dword ptr [rip + 0xab0fda]
14003ef22:  movss  xmm12, dword ptr [rip + 0xab1005]
14003ef2b:  test   sil, sil
14003ef2e:  jne    0x14003f549
14003ef34:  cmp    qword ptr [r13 + 0x78], rbx
14003ef38:  je     0x14003f549
14003ef3e:  lea    rax, [rip + 0xb6181b]   ; ->0x140ba0760
14003ef45:  mov    dword ptr [rbp + 0x78], ebx
14003ef48:  lea    rdx, [rbp + 0x28]
14003ef4c:  mov    qword ptr [rbp + 0x80], rax
14003ef53:  lea    rcx, [rbp + 0x12a0]
14003ef5a:  call   0x140071c40
14003ef5f:  lea    rcx, [rbp + 0x12a0]
14003ef66:  call   0x14000e180
14003ef6b:  mov    rcx, rax
14003ef6e:  lea    rdx, [rip + 0xa75093]   ; "project.json"
14003ef75:  call   0x14000d880
14003ef7a:  mov    rdx, rax
14003ef7d:  lea    rcx, [rbp + 0xc48]
14003ef84:  call   0x140071c40
14003ef89:  lea    rcx, [rbp + 0x12a0]
14003ef90:  call   0x140071e80
14003ef95:  lea    rdx, [rbp + 0x58]
14003ef99:  lea    rcx, [rbp + 0x12c0]
14003efa0:  call   0x140071c40
14003efa5:  lea    rdx, [rip + 0xa7505c]   ; "project.json"
14003efac:  lea    rcx, [rbp + 0x12c0]
14003efb3:  call   0x14000d880
14003efb8:  mov    rdx, rax
14003efbb:  lea    rcx, [rbp + 0x8f0]
14003efc2:  call   0x140071c40
14003efc7:  lea    rcx, [rbp + 0x12c0]
14003efce:  call   0x140071e80
14003efd3:  mov    r8d, 2
14003efd9:  lea    rdx, [rbp + 0x8f0]
14003efe0:  lea    rcx, [rbp + 0xc48]
14003efe7:  call   0x14000e880
14003efec:  lea    rdx, [rip + 0xa767fd]   ; "wallpaper.mp4"
14003eff3:  lea    rcx, [rbp + 0x2a0]
14003effa:  call   0x140049aa0
14003efff:  lea    rcx, [rbp + 0xd40]
14003f006:  call   0x14002a1b0
14003f00b:  mov    ebx, 0x1e
14003f010:  mov    dword ptr [rbp + 0xd7c], r12d
14003f017:  lea    rdx, [rip + 0xa767e2]   ; "mp4_h264"
14003f01e:  mov    dword ptr [rbp + 0xd78], ebx
14003f024:  lea    rcx, [rbp + 0xd40]
14003f02b:  call   0x140049aa0
14003f030:  lea    rcx, [rbp + 0x8f0]
14003f037:  call   0x14000d7a0
14003f03c:  mov    rdx, rax
14003f03f:  lea    rcx, [rbp + 0x12e0]
14003f046:  call   0x1400496b0
14003f04b:  lea    rdx, [rbp + 0x12e0]
14003f052:  lea    rcx, [rbp + 0x2980]
14003f059:  call   0x1404ce0d0
14003f05e:  mov    rdx, rax
14003f061:  lea    rcx, [rbp + 0xda0]
14003f068:  call   0x14002a3d0
14003f06d:  lea    rcx, [rbp + 0x2980]
14003f074:  call   0x14001bdd0
14003f079:  lea    rcx, [rbp + 0x12e0]
14003f080:  call   0x140071e80
14003f085:  lea    rdx, [rbp + 0x58]
14003f089:  lea    rcx, [rbp + 0x1300]
14003f090:  call   0x140071c40
14003f095:  lea    rdx, [rbp + 0x2a0]
14003f09c:  lea    rcx, [rbp + 0x1300]
14003f0a3:  call   0x14000da30
14003f0a8:  mov    rdx, rax
14003f0ab:  lea    rcx, [rbp + 0xd80]
14003f0b2:  call   0x140019260
14003f0b7:  lea    rcx, [rbp + 0x1300]
14003f0be:  call   0x140071e80
14003f0c3:  lea    rdx, [r13 + 0xc0]
14003f0ca:  lea    rcx, [rbp + 0xe20]
14003f0d1:  call   0x1402353c0
14003f0d6:  mov    edi, dword ptr [r13 + 0x88]
14003f0dd:  lea    rdx, [rip + 0xa76728]   ; "1080p"
14003f0e4:  mov    eax, 0x3c
14003f0e9:  mov    dword ptr [rbp + 0xd64], 0x780
14003f0f3:  cmp    edi, eax
14003f0f5:  mov    dword ptr [rbp + 0xd60], 0x438
14003f0ff:  mov    esi, 0x7a1200
14003f104:  cmove  ebx, eax
14003f107:  mov    dword ptr [rbp + 0xd74], esi
14003f10d:  mov    dword ptr [rbp + 0xd70], ebx
14003f113:  lea    rcx, [r13 + 0x68]
14003f117:  call   0x140042480
14003f11c:  test   al, al
14003f11e:  je     0x14003f136
14003f120:  cmp    edi, 0x3c
14003f123:  mov    eax, 0xf42400
14003f128:  cmove  esi, eax
14003f12b:  mov    dword ptr [rbp + 0xd74], esi
14003f131:  jmp    0x14003f209
14003f136:  lea    rdx, [rip + 0xa766d7]   ; "4k"
14003f13d:  lea    rcx, [r13 + 0x68]
14003f141:  call   0x140042480
14003f146:  test   al, al
14003f148:  je     0x14003f173
14003f14a:  cmp    edi, 0x3c
14003f14d:  mov    dword ptr [rbp + 0xd64], 0xf00
14003f157:  mov    eax, 0xf42400
14003f15c:  mov    dword ptr [rbp + 0xd60], 0x870
14003f166:  mov    ecx, 0x1312d00
14003f16b:  cmove  eax, ecx
14003f16e:  jmp    0x14003f203
14003f173:  lea    rdx, [rip + 0xa75006]   ; "auto"
14003f17a:  lea    rcx, [rbp + 0x15b0]
14003f181:  call   0x140070ce0
14003f186:  lea    rdx, [rbp - 0x80]
14003f18a:  lea    rcx, [r13 + 0x68]
14003f18e:  movups xmm0, xmmword ptr [rax]
14003f191:  movaps xmmword ptr [rbp - 0x80], xmm0
14003f195:  call   0x1400497e0
14003f19a:  test   al, al
14003f19c:  je     0x14003f209
14003f19e:  mov    edx, dword ptr [r13 + 0xb4]
14003f1a5:  cmp    edx, 4
14003f1a8:  jle    0x14003f209
14003f1aa:  mov    eax, dword ptr [r13 + 0xb8]
14003f1b1:  cmp    eax, 4
14003f1b4:  jle    0x14003f209
14003f1b6:  cmp    edx, eax
14003f1b8:  jle    0x14003f1e7
14003f1ba:  mov    ecx, eax
14003f1bc:  mov    dword ptr [rbp + 0xd60], eax
14003f1c2:  mov    eax, edx
14003f1c4:  mov    dword ptr [rbp + 0xd64], edx
14003f1ca:  imul   ecx, eax
14003f1cd:  cmp    ecx, 0x384000
14003f1d3:  jb     0x14003f1f9
14003f1d5:  cmp    edi, 0x3c
14003f1d8:  jne    0x14003f1fe
14003f1da:  mov    ecx, 0x1312d00
14003f1df:  mov    dword ptr [rbp + 0xd74], ecx
14003f1e5:  jmp    0x14003f209
14003f1e7:  mov    ecx, edx
14003f1e9:  mov    dword ptr [rbp + 0xd60], edx
14003f1ef:  jge    0x14003f1c2
14003f1f1:  mov    dword ptr [rbp + 0xd64], eax
14003f1f7:  jmp    0x14003f1ca
14003f1f9:  cmp    edi, 0x3c
14003f1fc:  jne    0x14003f209
14003f1fe:  mov    eax, 0xf42400
14003f203:  mov    dword ptr [rbp + 0xd74], eax
14003f209:  lea    rcx, [r13 + 0x90]
14003f210:  lea    rdx, [rip + 0xa73cb9]   ; "disabled"
14003f217:  call   0x140042480
14003f21c:  test   al, al
14003f21e:  je     0x14003f27b
14003f220:  xor    eax, eax
14003f222:  lea    rdx, [rip + 0xa765e3]   ; "1080p"
14003f229:  lea    rcx, [r13 + 0x68]
14003f22d:  mov    qword ptr [rbp + 0xd60], rax
14003f234:  call   0x140042480
14003f239:  test   al, al
14003f23b:  je     0x14003f253
14003f23d:  mov    dword ptr [rbp + 0xd6c], 0x780
14003f247:  mov    dword ptr [rbp + 0xd68], 0x438
14003f251:  jmp    0x14003f27b
14003f253:  lea    rdx, [rip + 0xa765ba]   ; "4k"
14003f25a:  lea    rcx, [r13 + 0x68]
14003f25e:  call   0x140042480
14003f263:  test   al, al
14003f265:  je     0x14003f27b
14003f267:  mov    dword ptr [rbp + 0xd6c], 0xf00
14003f271:  mov    dword ptr [rbp + 0xd68], 0x870
14003f27b:  lea    rdx, [rip + 0xa76596]   ; "alignmentposition"
14003f282:  lea    rcx, [rbp + 0x15c0]
14003f289:  call   0x140070ce0
14003f28e:  lea    rdx, [rbp - 0x80]
14003f292:  lea    rcx, [rbp + 0xe20]
14003f299:  movups xmm0, xmmword ptr [rax]
14003f29c:  movaps xmmword ptr [rbp - 0x80], xmm0
14003f2a0:  call   0x1402372e0
14003f2a5:  mov    edx, dword ptr [r13 + 0xb0]
14003f2ac:  lea    rcx, [rbp + 0x1a30]
14003f2b3:  mov    rbx, rax
14003f2b6:  call   0x1400695f0
14003f2bb:  mov    r8, rax
14003f2be:  lea    rdx, [rip + 0xa7656b]   ; "{"value":"
14003f2c5:  lea    rcx, [rbp + 0x1a10]
14003f2cc:  call   0x140042e90
14003f2d1:  mov    rdx, rax
14003f2d4:  lea    r8, [rip + 0xa7555d]   ; "}"
14003f2db:  lea    rcx, [rbp + 0x19f0]
14003f2e2:  call   0x1400425e0
14003f2e7:  mov    rcx, rax
14003f2ea:  xor    r8d, r8d
14003f2ed:  mov    rdx, rbx
14003f2f0:  call   0x14000fcb0
14003f2f5:  lea    rcx, [rbp + 0x19f0]
14003f2fc:  call   0x140072260
14003f301:  lea    rcx, [rbp + 0x1a10]
14003f308:  call   0x140072260
14003f30d:  lea    rcx, [rbp + 0x1a30]
14003f314:  call   0x140072260
14003f319:  cmp    byte ptr [rbp + 0xdc9], 0
14003f320:  jne    0x14003f36b
14003f322:  lea    rdx, [rip + 0xa76517]   ; "ui_browse_mobile_transcoder_error_failed_copying_project_file"
14003f329:  lea    rcx, [rsp + 0x30]
14003f32e:  call   0x140071970
14003f333:  lea    rdx, [rsp + 0x30]
14003f338:  mov    byte ptr [rsp + 0x50], r12b
14003f33d:  lea    rcx, [rbp + 0x2460]
14003f344:  mov    dword ptr [rsp + 0x54], 0xbf800000
14003f34c:  call   0x140042520
14003f351:  mov    rdx, rax
14003f354:  mov    rcx, r15
14003f357:  call   0x1400472f0
14003f35c:  lea    rcx, [rsp + 0x30]
14003f361:  call   0x140072260
14003f366:  jmp    0x14003f520
14003f36b:  lea    rcx, [rbp + 0x2760]
14003f372:  call   0x140214a70
14003f377:  lea    rdx, [rbp + 0xd40]
14003f37e:  lea    rcx, [rbp + 0x2b90]
14003f385:  call   0x14002a7f0
14003f38a:  mov    rdx, rax
14003f38d:  lea    rcx, [rbp + 0x2760]
14003f394:  call   0x140217790
14003f399:  movss  xmm6, dword ptr [rip + 0xab0eaf]
14003f3a1:  movss  xmm8, dword ptr [rip + 0xab0ab2]
14003f3aa:  nop    word ptr [rax + rax]
14003f3b0:  lea    rcx, [rsp + 0x30]
14003f3b5:  call   0x1400424f0
14003f3ba:  lea    rdx, [rsp + 0x30]
14003f3bf:  lea    rcx, [rbp + 0x2760]
14003f3c6:  call   0x140214bd0
14003f3cb:  test   al, al
14003f3cd:  jne    0x14003f404
14003f3cf:  mov    ecx, 0x3e8
14003f3d4:  call   qword ptr [rip + 0x9a35b6]   ; KERNEL32.dll!Sleep
14003f3da:  mov    edx, 5
14003f3df:  lea    rcx, [r13 + 0x1b0]
14003f3e6:  call   0x14004fab0
14003f3eb:  test   al, al
14003f3ed:  je     0x14003f47a
14003f3f3:  lea    rcx, [rbp + 0x2760]
14003f3fa:  call   0x140217860
14003f3ff:  jmp    0x14003f50a
14003f404:  cmp    qword ptr [rsp + 0x48], 0
14003f40a:  lea    rcx, [rbp - 0x70]
14003f40e:  jne    0x14003f4cf
14003f414:  lea    rdx, [rip + 0xa76095]   ; "ui_browse_mobile_transcoder_progress_converting_project"
14003f41b:  call   0x140071970
14003f420:  movd   xmm0, dword ptr [rsp + 0x34]
14003f426:  cvtdq2ps xmm0, xmm0
14003f429:  mov    byte ptr [rbp - 0x50], 0
14003f42d:  mulss  xmm0, xmm11
14003f432:  divss  xmm0, xmm6
14003f436:  addss  xmm0, xmm8
14003f43b:  comiss xmm12, xmm0
14003f43f:  jbe    0x14003f448
14003f441:  movss  dword ptr [rbp - 0x4c], xmm0
14003f446:  jmp    0x14003f44f
14003f448:  mov    dword ptr [rbp - 0x4c], 0x3f7d70a4
14003f44f:  lea    rdx, [rbp - 0x70]
14003f453:  lea    rcx, [rbp + 0x2488]
14003f45a:  call   0x140042520
14003f45f:  mov    rdx, rax
14003f462:  mov    rcx, r15
14003f465:  call   0x1400472f0
14003f46a:  lea    rcx, [rbp - 0x70]
14003f46e:  call   0x140072260
14003f473:  cmp    byte ptr [rsp + 0x30], 0
14003f478:  jne    0x14003f489
14003f47a:  lea    rcx, [rsp + 0x38]
14003f47f:  call   0x140072260
14003f484:  jmp    0x14003f3b0
14003f489:  lea    rcx, [rsp + 0x38]
14003f48e:  mov    byte ptr [rbp + 0x2da0], r12b
14003f495:  call   0x140072260
14003f49a:  lea    rcx, [rbp + 0x2760]
14003f4a1:  call   0x140214b70
14003f4a6:  lea    rcx, [rbp + 0xd40]
14003f4ad:  call   0x14002a760
14003f4b2:  lea    rcx, [rbp + 0x8f0]
14003f4b9:  call   0x140071e80
14003f4be:  lea    rcx, [rbp + 0xc48]
14003f4c5:  call   0x140071e80
14003f4ca:  jmp    0x140040137
14003f4cf:  lea    rdx, [rip + 0xa763aa]   ; "ui_browse_mobile_transcoder_error_failed_converting_video"
14003f4d6:  call   0x140071970
14003f4db:  lea    rdx, [rbp - 0x70]
14003f4df:  mov    byte ptr [rbp - 0x50], r12b
14003f4e3:  lea    rcx, [rbp + 0x24b0]
14003f4ea:  mov    dword ptr [rbp - 0x4c], 0xbf800000
14003f4f1:  call   0x140042520
14003f4f6:  mov    rdx, rax
14003f4f9:  mov    rcx, r15
14003f4fc:  call   0x1400472f0
14003f501:  lea    rcx, [rbp - 0x70]
14003f505:  call   0x140072260
14003f50a:  lea    rcx, [rsp + 0x38]
14003f50f:  call   0x140072260
14003f514:  lea    rcx, [rbp + 0x2760]
14003f51b:  call   0x140214b70
14003f520:  lea    rcx, [rbp + 0xd40]
14003f527:  call   0x14002a760
14003f52c:  lea    rcx, [rbp + 0x8f0]
14003f533:  call   0x140071e80
14003f538:  lea    rcx, [rbp + 0xc48]
14003f53f:  call   0x140071e80
14003f544:  jmp    0x140042024
14003f549:  mov    byte ptr [rbp + 0x2da0], bl
14003f54f:  lea    rax, [rip + 0xb6120a]   ; ->0x140ba0760
14003f556:  mov    qword ptr [rbp + 0x80], rax
14003f55d:  mov    dword ptr [rbp + 0x78], ebx
14003f560:  test   sil, sil
14003f563:  jne    0x1400400ea
14003f569:  lea    rcx, [rbp + 0x28]
14003f56d:  call   0x14000e2e0
14003f572:  test   al, al
14003f574:  je     0x14003f588
14003f576:  lea    rdx, [rbp + 0xb08]
14003f57d:  lea    rcx, [rbp + 0x28]
14003f581:  call   0x14000e090
14003f586:  jmp    0x14003f59b
14003f588:  lea    rdx, [rip + 0xa88192]   ; ->0x140ac7721
14003f58f:  lea    rcx, [rbp + 0xb08]
14003f596:  call   0x14000d0f0
14003f59b:  lea    rdx, [rbp + 0x1a50]
14003f5a2:  lea    rcx, [rbp + 0xb08]
14003f5a9:  call   0x14000dc30
14003f5ae:  mov    rcx, rax
14003f5b1:  lea    rdx, [rbp + 0x950]
14003f5b8:  call   0x14000dab0
14003f5bd:  lea    rcx, [rbp + 0x1a50]
14003f5c4:  call   0x140071e80
14003f5c9:  test   dil, dil
14003f5cc:  je     0x14003f60a
14003f5ce:  cmp    qword ptr [rbp + 0x960], rbx
14003f5d5:  je     0x14003f60a
14003f5d7:  xor    edx, edx
14003f5d9:  lea    rcx, [rbp + 0x950]
14003f5e0:  call   0x140072190
14003f5e5:  movsx  ecx, byte ptr [rax]
14003f5e8:  call   0x14096e780
14003f5ed:  test   eax, eax
14003f5ef:  je     0x14003f60a
14003f5f1:  lea    rcx, [rbp + 0x950]
14003f5f8:  call   0x1400498e0
14003f5fd:  mov    rcx, rax
14003f600:  call   0x14000cd50
14003f605:  mov    rsi, rax
14003f608:  jmp    0x14003f60d
14003f60a:  mov    rsi, rbx
14003f60d:  lea    rdx, [rbp + 0x58]
14003f611:  lea    rcx, [rbp + 0x1320]
14003f618:  call   0x140071c40
14003f61d:  lea    r9, [rbp + 0x78]
14003f621:  lea    rdx, [rbp + 0x1320]
14003f628:  lea    rcx, [rbp + 0x358]
14003f62f:  call   0x140068de0
14003f634:  lea    rcx, [rbp + 0x1320]
14003f63b:  call   0x140071e80
14003f640:  lea    rdx, [rbp + 0x358]
14003f647:  lea    rcx, [rbp + 0x15d0]
14003f64e:  call   0x140017f80
14003f653:  mov    rdx, rax
14003f656:  lea    rcx, [rbp - 0x28]
14003f65a:  call   0x140068c60
14003f65f:  lea    rdx, [rbp + 0x358]
14003f666:  lea    rcx, [rbp + 0x15e0]
14003f66d:  call   0x140017f80
14003f672:  mov    rdx, rax
14003f675:  lea    rcx, [rbp - 0x80]
14003f679:  call   0x140068be0
14003f67e:  mov    rbx, qword ptr [rbp - 0x28]
14003f682:  mov    r12, qword ptr [rbp - 0x80]
14003f686:  cmp    rbx, r12
14003f689:  je     0x1400400ae
14003f68f:  mov    r15, qword ptr [rbp + 0x2d90]
14003f696:  nop    word ptr [rax + rax]
14003f6a0:  mov    edx, 5
14003f6a5:  lea    rcx, [r13 + 0x1b0]
14003f6ac:  call   0x14004fab0
14003f6b1:  test   al, al
14003f6b3:  jne    0x1400400a3
14003f6b9:  lea    rdx, [rbx + 0x20]
14003f6bd:  lea    rcx, [rbp + 0x1d0]
14003f6c4:  call   0x140071c40
14003f6c9:  lea    rcx, [rbp + 0x1d0]
14003f6d0:  call   0x14000e3b0
14003f6d5:  test   al, al
14003f6d7:  je     0x140040081
14003f6dd:  lea    r8, [rbp + 0x58]
14003f6e1:  lea    rdx, [rbp + 0x1d0]
14003f6e8:  lea    rcx, [rbp + 0x530]
14003f6ef:  call   0x14000ecf0
14003f6f4:  lea    rdx, [rbp + 0x1a70]
14003f6fb:  lea    rcx, [rbp + 0x530]
14003f702:  call   0x14000e490
14003f707:  mov    rcx, rax
14003f70a:  lea    rdx, [rbp - 0x70]
14003f70e:  call   0x14000dab0
14003f713:  lea    rcx, [rbp + 0x1a70]
14003f71a:  call   0x140071e80
14003f71f:  lea    rcx, [rbp + 0x1d0]
14003f726:  call   0x14000e2e0
14003f72b:  test   al, al
14003f72d:  je     0x14003f744
14003f72f:  lea    rdx, [rbp + 0x930]
14003f736:  lea    rcx, [rbp + 0x1d0]
14003f73d:  call   0x14000e090
14003f742:  jmp    0x14003f757
14003f744:  lea    rdx, [rip + 0xa87fd6]   ; ->0x140ac7721
14003f74b:  lea    rcx, [rbp + 0x930]
14003f752:  call   0x14000d0f0
14003f757:  lea    rcx, [rbp + 0x930]
14003f75e:  call   0x14000e2e0
14003f763:  test   al, al
14003f765:  je     0x14003f77c
14003f767:  lea    rdx, [rbp + 0x578]
14003f76e:  lea    rcx, [rbp + 0x930]
14003f775:  call   0x14000e090
14003f77a:  jmp    0x14003f78f
14003f77c:  lea    rdx, [rip + 0xa87f9e]   ; ->0x140ac7721
14003f783:  lea    rcx, [rbp + 0x578]
14003f78a:  call   0x14000d0f0
14003f78f:  lea    rcx, [rbp + 0x578]
14003f796:  call   0x14000e2e0
14003f79b:  test   al, al
14003f79d:  je     0x14003f7b4
14003f79f:  lea    rdx, [rbp + 0xb28]
14003f7a6:  lea    rcx, [rbp + 0x578]
14003f7ad:  call   0x14000e090
14003f7b2:  jmp    0x14003f7c7
14003f7b4:  lea    rdx, [rip + 0xa87f66]   ; ->0x140ac7721
14003f7bb:  lea    rcx, [rbp + 0xb28]
14003f7c2:  call   0x14000d0f0
14003f7c7:  lea    rdx, [rbp + 0x378]
14003f7ce:  lea    rcx, [rbp + 0x1d0]
14003f7d5:  call   0x14000dc90
14003f7da:  lea    rcx, [rbp + 0x530]
14003f7e1:  call   0x14000e580
14003f7e6:  lea    rdx, [rbp + 0x1a90]
14003f7ed:  lea    rcx, [rbp + 0x378]
14003f7f4:  call   0x14000dab0
14003f7f9:  mov    rcx, rax
14003f7fc:  lea    rdx, [rip + 0xa760b9]   ; ".tex"
14003f803:  call   0x140042480
14003f808:  lea    rcx, [rbp + 0x1a90]
14003f80f:  movzx  ebx, al
14003f812:  and    r14d, 0xfffffffb
14003f816:  call   0x140072260
14003f81b:  test   bl, bl
14003f81d:  je     0x14003f9e9
14003f823:  lea    rdx, [rip + 0xa7609e]   ; "materials"
14003f82a:  lea    rcx, [rbp + 0x1360]
14003f831:  call   0x14000d0f0
14003f836:  lea    rdx, [rbp + 0x1360]
14003f83d:  lea    rcx, [rbp + 0x530]
14003f844:  call   0x14006c300
14003f849:  test   al, al
14003f84b:  jne    0x14003f880
14003f84d:  lea    rdx, [rip + 0xa76084]   ; "materials\masks"
14003f854:  lea    rcx, [rbp + 0x1340]
14003f85b:  call   0x14000d0f0
14003f860:  lea    rdx, [rbp + 0x1340]
14003f867:  or     r14d, 0x18
14003f86b:  lea    rcx, [rbp + 0x530]
14003f872:  call   0x14006c300
14003f877:  test   al, al
14003f879:  jne    0x14003f884
14003f87b:  xor    dil, dil
14003f87e:  jmp    0x14003f887
14003f880:  or     r14d, 8
14003f884:  mov    dil, 1
14003f887:  test   r14b, 0x10
14003f88b:  je     0x14003f89d
14003f88d:  and    r14d, 0xffffffef
14003f891:  lea    rcx, [rbp + 0x1340]
14003f898:  call   0x140071e80
14003f89d:  lea    rcx, [rbp + 0x1360]
14003f8a4:  and    r14d, 0xfffffff7
14003f8a8:  call   0x140071e80
14003f8ad:  mov    edx, 1
14003f8b2:  lea    rcx, [rbp + 0x2270]
14003f8b9:  call   0x14004b800
14003f8be:  lea    rdx, [rip + 0xa7602b]   ; "-transcode -i ""
14003f8c5:  lea    rcx, [rbp + 0x2280]
14003f8cc:  call   0x140042940
14003f8d1:  lea    rdx, [rbp + 0x1ad0]
14003f8d8:  mov    rbx, rax
14003f8db:  lea    rcx, [rbp + 0x1d0]
14003f8e2:  call   0x14000dab0
14003f8e7:  mov    rdx, rax
14003f8ea:  mov    rcx, rbx
14003f8ed:  call   0x140042de0
14003f8f2:  mov    rcx, rax
14003f8f5:  lea    rdx, [rip + 0xa75fec]   ; "" -o ""
14003f8fc:  call   0x140042940
14003f901:  lea    rdx, [rbp + 0x1ab0]
14003f908:  mov    rbx, rax
14003f90b:  lea    rcx, [rbp + 0x1d0]
14003f912:  call   0x14000dab0
14003f917:  mov    rdx, rax
14003f91a:  mov    rcx, rbx
14003f91d:  call   0x140042de0
14003f922:  mov    rcx, rax
14003f925:  lea    rdx, [rip + 0xa742f0]   ; """
14003f92c:  call   0x140042940
14003f931:  lea    rcx, [rbp + 0x1ab0]
14003f938:  call   0x140072260
14003f93d:  lea    rcx, [rbp + 0x1ad0]
14003f944:  call   0x140072260
14003f949:  lea    rdx, [rip + 0xa75fb0]   ; " -f ETC2"
14003f950:  lea    rcx, [rbp + 0x2280]
14003f957:  call   0x140042940
14003f95c:  test   dil, dil
14003f95f:  je     0x14003f97b
14003f961:  cmp    dword ptr [r15 + 0x60], 1
14003f966:  jne    0x14003f97b
14003f968:  lea    rdx, [rip + 0xa75fa1]   ; " -c force"
14003f96f:  lea    rcx, [rbp + 0x2280]
14003f976:  call   0x140042940
14003f97b:  lea    rdx, [rip + 0xa75f9e]   ; " -shrink "
14003f982:  lea    rcx, [rbp + 0x2280]
14003f989:  call   0x140042940
14003f98e:  mov    edx, dword ptr [r15 + 0x64]
14003f992:  mov    rcx, rax
14003f995:  call   0x14004bba0
14003f99a:  lea    rdx, [rip + 0xa75f8f]   ; " -maxmipmaps 1"
14003f9a1:  lea    rcx, [rbp + 0x2280]
14003f9a8:  call   0x140042940
14003f9ad:  lea    rdx, [rbp + 0x1af0]
14003f9b4:  lea    rcx, [rbp + 0x2270]
14003f9bb:  call   0x14004b680
14003f9c0:  mov    rdx, rax
14003f9c3:  lea    rcx, [rbp - 0x18]
14003f9c7:  call   0x140049160
14003f9cc:  lea    rcx, [rbp + 0x1af0]
14003f9d3:  call   0x140072260
14003f9d8:  lea    rcx, [rbp + 0x2270]
14003f9df:  call   0x14000c5e0
14003f9e4:  jmp    0x14004003c
14003f9e9:  lea    rdx, [rip + 0xa73180]   ; "shaders"
14003f9f0:  lea    rcx, [rbp - 0x70]
14003f9f4:  call   0x140042480
14003f9f9:  test   al, al
14003f9fb:  je     0x14003fa92
14003fa01:  lea    rdx, [rip + 0xa73154]   ; ".vert"
14003fa08:  lea    rcx, [rbp + 0x13c0]
14003fa0f:  call   0x14000d0f0
14003fa14:  lea    rdx, [rbp + 0x13c0]
14003fa1b:  lea    rcx, [rbp + 0x378]
14003fa22:  call   0x14006c300
14003fa27:  test   al, al
14003fa29:  jne    0x14003fd8d
14003fa2f:  lea    rdx, [rip + 0xa7311e]   ; ".frag"
14003fa36:  lea    rcx, [rbp + 0x13a0]
14003fa3d:  call   0x14000d0f0
14003fa42:  lea    rdx, [rbp + 0x13a0]
14003fa49:  lea    rcx, [rbp + 0x378]
14003fa50:  call   0x14006c300
14003fa55:  test   al, al
14003fa57:  jne    0x14003fd82
14003fa5d:  lea    rdx, [rip + 0xa73100]   ; ".geom"
14003fa64:  lea    rcx, [rbp + 0x1380]
14003fa6b:  call   0x14000d0f0
14003fa70:  lea    rdx, [rbp + 0x1380]
14003fa77:  or     r14d, 0xe0
14003fa7e:  lea    rcx, [rbp + 0x378]
14003fa85:  call   0x14006c300
14003fa8a:  test   al, al
14003fa8c:  jne    0x14003fd91
14003fa92:  xor    bl, bl
14003fa94:  test   r14b, r14b
14003fa97:  jns    0x14003faaa
14003fa99:  btr    r14d, 7
14003fa9e:  lea    rcx, [rbp + 0x1380]
14003faa5:  call   0x140071e80
14003faaa:  test   r14b, 0x40
14003faae:  je     0x14003fac0
14003fab0:  and    r14d, 0xffffffbf
14003fab4:  lea    rcx, [rbp + 0x13a0]
14003fabb:  call   0x140071e80
14003fac0:  test   r14b, 0x20
14003fac4:  je     0x14003fad6
14003fac6:  and    r14d, 0xffffffdf
14003faca:  lea    rcx, [rbp + 0x13c0]
14003fad1:  call   0x140071e80
14003fad6:  test   bl, bl
14003fad8:  je     0x14003ffdd
14003fade:  lea    rcx, [rbp + 0x1d0]
14003fae5:  call   0x14000d7a0
14003faea:  mov    rdx, rax
14003faed:  lea    rcx, [rbp + 0x6b8]
14003faf4:  call   0x140014570
14003faf9:  lea    rdx, [rbp + 0x1b30]
14003fb00:  lea    rcx, [rbp + 0xb28]
14003fb07:  call   0x14000dc30
14003fb0c:  mov    rcx, rax
14003fb0f:  lea    rdx, [rbp + 0x1b10]
14003fb16:  call   0x14000dab0
14003fb1b:  mov    rcx, rax
14003fb1e:  call   0x1400498e0
14003fb23:  mov    rcx, rax
14003fb26:  lea    rdx, [rip + 0xa75e13]   ; "workshop"
14003fb2d:  call   0x14096e390
14003fb32:  lea    rcx, [rbp + 0x1b10]
14003fb39:  mov    ebx, eax
14003fb3b:  call   0x140072260
14003fb40:  lea    rcx, [rbp + 0x1b30]
14003fb47:  call   0x140071e80
14003fb4c:  test   ebx, ebx
14003fb4e:  jne    0x14003ff5f
14003fb54:  lea    rdx, [rbp + 0x1b50]
14003fb5b:  lea    rcx, [rbp + 0x578]
14003fb62:  call   0x14000dc30
14003fb67:  mov    rcx, rax
14003fb6a:  lea    rdx, [rbp + 0x14a0]
14003fb71:  call   0x14000dab0
14003fb76:  lea    rcx, [rbp + 0x1b50]
14003fb7d:  call   0x140071e80
14003fb82:  lea    rcx, [rbp + 0x1b70]
14003fb89:  call   0x14000e700
14003fb8e:  mov    rcx, rax
14003fb91:  call   0x14000e580
14003fb96:  mov    rcx, rax
14003fb99:  lea    rdx, [rip + 0xa75db0]   ; "assets/zcompat/scene/shaders"
14003fba0:  call   0x14000d880
14003fba5:  mov    rcx, rax
14003fba8:  lea    rdx, [rbp + 0x14a0]
14003fbaf:  call   0x14000da30
14003fbb4:  mov    rdx, rax
14003fbb7:  lea    rcx, [rbp + 0x910]
14003fbbe:  call   0x140071c40
14003fbc3:  lea    rcx, [rbp + 0x1b70]
14003fbca:  call   0x140071e80
14003fbcf:  lea    rdx, [rbp + 0x910]
14003fbd6:  lea    rcx, [rbp + 0x13e0]
14003fbdd:  call   0x140071c40
14003fbe2:  lea    rdx, [rip + 0xa75d87]   ; "config.json"
14003fbe9:  lea    rcx, [rbp + 0x13e0]
14003fbf0:  call   0x14000d880
14003fbf5:  mov    rdx, rax
14003fbf8:  lea    rcx, [rbp + 0xb68]
14003fbff:  call   0x140071c40
14003fc04:  lea    rcx, [rbp + 0x13e0]
14003fc0b:  call   0x140071e80
14003fc10:  lea    rcx, [rbp + 0xb68]
14003fc17:  call   0x14000e6a0
14003fc1c:  test   al, al
14003fc1e:  je     0x14003ff3b
14003fc24:  lea    rcx, [rbp + 0xb68]
14003fc2b:  call   0x14000d7a0
14003fc30:  mov    rdx, rax
14003fc33:  lea    rcx, [rbp + 0x1480]
14003fc3a:  call   0x140014570
14003fc3f:  xor    edx, edx
14003fc41:  lea    rcx, [rbp + 0x750]
14003fc48:  call   0x140234fb0
14003fc4d:  xor    r8d, r8d
14003fc50:  lea    rdx, [rbp + 0x750]
14003fc57:  lea    rcx, [rbp + 0x1480]
14003fc5e:  call   0x14000fcb0
14003fc63:  test   al, al
14003fc65:  je     0x14003ff23
14003fc6b:  cmp    byte ptr [rbp + 0x758], 7
14003fc72:  jne    0x14003ff23
14003fc78:  lea    rdx, [rip + 0xa75cfd]   ; "frag"
14003fc7f:  lea    rcx, [rbp + 0x2140]
14003fc86:  call   0x140070ce0
14003fc8b:  lea    rdx, [rsp + 0x60]
14003fc90:  lea    rcx, [rbp + 0x750]
14003fc97:  movups xmm0, xmmword ptr [rax]
14003fc9a:  movaps xmmword ptr [rsp + 0x60], xmm0
14003fc9f:  call   0x1402372e0
14003fca4:  mov    rdx, rax
14003fca7:  lea    rcx, [rbp]
14003fcab:  call   0x140235280
14003fcb0:  lea    rdx, [rip + 0xa75ccd]   ; "vert"
14003fcb7:  lea    rcx, [rbp + 0x2130]
14003fcbe:  call   0x140070ce0
14003fcc3:  lea    rdx, [rsp + 0x60]
14003fcc8:  lea    rcx, [rbp + 0x750]
14003fccf:  movups xmm0, xmmword ptr [rax]
14003fcd2:  movaps xmmword ptr [rsp + 0x60], xmm0
14003fcd7:  call   0x1402372e0
14003fcdc:  mov    rdx, rax
14003fcdf:  lea    rcx, [rbp + 0x700]
14003fce6:  call   0x140235280
14003fceb:  lea    rdx, [rip + 0xa75c9e]   ; "maximumprojectid"
14003fcf2:  lea    rcx, [rbp + 0x20b0]
14003fcf9:  call   0x140070ce0
14003fcfe:  lea    rdx, [rsp + 0x60]
14003fd03:  lea    rcx, [rbp + 0x750]
14003fd0a:  movups xmm0, xmmword ptr [rax]
14003fd0d:  movaps xmmword ptr [rsp + 0x60], xmm0
14003fd12:  call   0x1402372e0
14003fd17:  mov    rdx, rax
14003fd1a:  lea    rcx, [rsp + 0x30]
14003fd1f:  call   0x140235280
14003fd24:  cmp    byte ptr [rsp + 0x38], 4
14003fd29:  jne    0x14003fd42
14003fd2b:  test   rsi, rsi
14003fd2e:  je     0x14003fd42
14003fd30:  lea    rcx, [rsp + 0x30]
14003fd35:  call   0x1402357b0
14003fd3a:  mov    rcx, rax
14003fd3d:  call   0x14000cd50
14003fd42:  lea    rcx, [rbp + 0x698]
14003fd49:  call   0x14001c1f0
14003fd4e:  lea    rdx, [rip + 0xa72dff]   ; ".frag"
14003fd55:  lea    rcx, [rbp + 0x1400]
14003fd5c:  call   0x14000d0f0
14003fd61:  lea    rdx, [rbp + 0x1400]
14003fd68:  lea    rcx, [rbp + 0x378]
14003fd6f:  call   0x14006c300
14003fd74:  test   al, al
14003fd76:  je     0x14003fd98
14003fd78:  cmp    byte ptr [rbp + 8], 4
14003fd7c:  jne    0x14003fd98
14003fd7e:  mov    bl, 1
14003fd80:  jmp    0x14003fd9a
14003fd82:  or     r14d, 0x60
14003fd86:  mov    bl, 1
14003fd88:  jmp    0x14003fa94
14003fd8d:  or     r14d, 0x20
14003fd91:  mov    bl, 1
14003fd93:  jmp    0x14003fa94
14003fd98:  xor    bl, bl
14003fd9a:  lea    rcx, [rbp + 0x1400]
14003fda1:  btr    r14d, 8
14003fda6:  call   0x140071e80
14003fdab:  test   bl, bl
14003fdad:  je     0x14003fe08
14003fdaf:  lea    rdx, [rbp + 0x910]
14003fdb6:  lea    rcx, [rbp + 0x1420]
14003fdbd:  call   0x140071c40
14003fdc2:  lea    rdx, [rbp + 0x1b90]
14003fdc9:  lea    rcx, [rbp]
14003fdcd:  call   0x1402357d0
14003fdd2:  mov    rdx, rax
14003fdd5:  lea    rcx, [rbp + 0x1420]
14003fddc:  call   0x14000da30
14003fde1:  mov    rdx, rax
14003fde4:  lea    rcx, [rbp + 0x698]
14003fdeb:  call   0x140019260
14003fdf0:  lea    rcx, [rbp + 0x1b90]
14003fdf7:  call   0x140072260
14003fdfc:  lea    rcx, [rbp + 0x1420]
14003fe03:  jmp    0x14003fead
14003fe08:  lea    rdx, [rip + 0xa72d4d]   ; ".vert"
14003fe0f:  lea    rcx, [rbp + 0x1440]
14003fe16:  call   0x14000d0f0
14003fe1b:  lea    rdx, [rbp + 0x1440]
14003fe22:  lea    rcx, [rbp + 0x378]
14003fe29:  call   0x14006c300
14003fe2e:  test   al, al
14003fe30:  je     0x14003fe3f
14003fe32:  cmp    byte ptr [rbp + 0x708], 4
14003fe39:  jne    0x14003fe3f
14003fe3b:  mov    bl, 1
14003fe3d:  jmp    0x14003fe41
14003fe3f:  xor    bl, bl
14003fe41:  lea    rcx, [rbp + 0x1440]
14003fe48:  call   0x140071e80
14003fe4d:  btr    r14d, 9
14003fe52:  test   bl, bl
14003fe54:  je     0x14003feb2
14003fe56:  lea    rdx, [rbp + 0x910]
14003fe5d:  lea    rcx, [rbp + 0x1460]
14003fe64:  call   0x140071c40
14003fe69:  lea    rdx, [rbp + 0x1bb0]
14003fe70:  lea    rcx, [rbp + 0x700]
14003fe77:  call   0x1402357d0
14003fe7c:  mov    rdx, rax
14003fe7f:  lea    rcx, [rbp + 0x1460]
14003fe86:  call   0x14000da30
14003fe8b:  mov    rdx, rax
14003fe8e:  lea    rcx, [rbp + 0x698]
14003fe95:  call   0x140019260
14003fe9a:  lea    rcx, [rbp + 0x1bb0]
14003fea1:  call   0x140072260
14003fea6:  lea    rcx, [rbp + 0x1460]
14003fead:  call   0x140071e80
14003feb2:  lea    rcx, [rbp + 0x698]
14003feb9:  call   0x14000e6a0
14003febe:  test   al, al
14003fec0:  je     0x14003fef8
14003fec2:  lea    rcx, [rbp + 0x698]
14003fec9:  call   0x14000d7a0
14003fece:  mov    rdx, rax
14003fed1:  lea    rcx, [rbp + 0x1bd0]
14003fed8:  call   0x140014570
14003fedd:  mov    rdx, rax
14003fee0:  lea    rcx, [rbp + 0x6b8]
14003fee7:  call   0x140049b10
14003feec:  lea    rcx, [rbp + 0x1bd0]
14003fef3:  call   0x140072260
14003fef8:  lea    rcx, [rbp + 0x698]
14003feff:  call   0x140071e80
14003ff04:  lea    rcx, [rsp + 0x30]
14003ff09:  call   0x140235370
14003ff0e:  lea    rcx, [rbp + 0x700]
14003ff15:  call   0x140235370
14003ff1a:  lea    rcx, [rbp]
14003ff1e:  call   0x140235370
14003ff23:  lea    rcx, [rbp + 0x750]
14003ff2a:  call   0x140235370
14003ff2f:  lea    rcx, [rbp + 0x1480]
14003ff36:  call   0x140072260
14003ff3b:  lea    rcx, [rbp + 0xb68]
14003ff42:  call   0x140071e80
14003ff47:  lea    rcx, [rbp + 0x910]
14003ff4e:  call   0x140071e80
14003ff53:  lea    rcx, [rbp + 0x14a0]
14003ff5a:  call   0x140072260
14003ff5f:  lea    rcx, [rbp + 0x6b8]
14003ff66:  call   0x140017dd0
14003ff6b:  lea    rdx, [rbp + 0x1c10]
14003ff72:  lea    rcx, [rbp + 0x1d0]
14003ff79:  call   0x14000df80
14003ff7e:  mov    rcx, rax
14003ff81:  lea    rdx, [rbp + 0x1bf0]
14003ff88:  call   0x14000dab0
14003ff8d:  mov    rcx, rax
14003ff90:  lea    rdx, [rbp + 0x6b8]
14003ff97:  call   0x140015f20
14003ff9c:  lea    rcx, [rbp + 0x1bf0]
14003ffa3:  call   0x140072260
14003ffa8:  lea    rcx, [rbp + 0x1c10]
14003ffaf:  call   0x140071e80
14003ffb4:  lea    rcx, [rbp + 0x1d0]
14003ffbb:  call   0x14000d7a0
14003ffc0:  mov    rcx, rax
14003ffc3:  lea    rdx, [rbp + 0x6b8]
14003ffca:  call   0x140014830
14003ffcf:  lea    rcx, [rbp + 0x6b8]
14003ffd6:  call   0x140072260
14003ffdb:  jmp    0x14004003c
14003ffdd:  lea    rdx, [rip + 0xa759c0]   ; "sounds"
14003ffe4:  lea    rcx, [rbp + 0x1c50]
14003ffeb:  call   0x140071970
14003fff0:  lea    rdx, [rbp + 0x1c30]
14003fff7:  mov    rbx, rax
14003fffa:  lea    rcx, [rbp + 0x530]
140040001:  call   0x14000dab0
140040006:  mov    rcx, rax
140040009:  mov    rdx, rbx
14004000c:  call   0x14000d0a0
140040011:  lea    rcx, [rbp + 0x1c30]
140040018:  movzx  ebx, al
14004001b:  call   0x140072260
140040020:  lea    rcx, [rbp + 0x1c50]
140040027:  call   0x140072260
14004002c:  test   bl, bl
14004002e:  je     0x14004003c
140040030:  lea    rcx, [rbp + 0x1d0]
140040037:  call   0x14000ea30
14004003c:  lea    rcx, [rbp + 0x378]
140040043:  call   0x140071e80
140040048:  lea    rcx, [rbp + 0xb28]
14004004f:  call   0x140071e80
140040054:  lea    rcx, [rbp + 0x578]
14004005b:  call   0x140071e80
140040060:  lea    rcx, [rbp + 0x930]
140040067:  call   0x140071e80
14004006c:  lea    rcx, [rbp - 0x70]
140040070:  call   0x140072260
140040075:  lea    rcx, [rbp + 0x530]
14004007c:  call   0x140071e80
140040081:  lea    rcx, [rbp + 0x1d0]
140040088:  call   0x140071e80
14004008d:  lea    rcx, [rbp - 0x28]
140040091:  call   0x140068710
140040096:  mov    rbx, qword ptr [rbp - 0x28]
14004009a:  cmp    rbx, r12
14004009d:  jne    0x14003f6a0
1400400a3:  mov    r15, qword ptr [rbp + 0x48]
1400400a7:  mov    r13, qword ptr [rbp + 0x2d90]
1400400ae:  lea    rcx, [rbp - 0x80]
1400400b2:  call   0x140068c90
1400400b7:  lea    rcx, [rbp - 0x28]
1400400bb:  call   0x140068c90
1400400c0:  lea    rcx, [rbp + 0x358]
1400400c7:  call   0x140068c90
1400400cc:  lea    rcx, [rbp + 0x950]
1400400d3:  call   0x140072260
1400400d8:  lea    rcx, [rbp + 0xb08]
1400400df:  call   0x140071e80
1400400e4:  mov    r12d, 1
1400400ea:  lea    rdx, [rip + 0xa753bf]   ; "ui_browse_mobile_transcoder_progress_converting_project"
1400400f1:  lea    rcx, [rsp + 0x30]
1400400f6:  call   0x140071970
1400400fb:  movss  xmm8, dword ptr [rip + 0xaafd58]
140040104:  lea    rdx, [rsp + 0x30]
140040109:  lea    rcx, [rbp + 0x24d8]
140040110:  mov    byte ptr [rsp + 0x50], 0
140040115:  mov    dword ptr [rsp + 0x54], 0x3e4ccccd
14004011d:  call   0x140042520
140040122:  mov    rdx, rax
140040125:  mov    rcx, r15
140040128:  call   0x1400472f0
14004012d:  lea    rcx, [rsp + 0x30]
140040132:  call   0x140072260
140040137:  mov    rcx, qword ptr [rbp - 0x10]
14004013b:  xorps  xmm7, xmm7
14004013e:  sub    rcx, qword ptr [rbp - 0x18]
140040142:  sar    rcx, 5
140040146:  test   rcx, rcx
140040149:  js     0x140040152
14004014b:  cvtsi2ss xmm7, rcx
140040150:  jmp    0x140040167
140040152:  mov    rax, rcx
140040155:  and    ecx, 1
140040158:  shr    rax, 1
14004015b:  or     rax, rcx
14004015e:  cvtsi2ss xmm7, rax
140040163:  addss  xmm7, xmm7
140040167:  xor    edi, edi
140040169:  xorps  xmm0, xmm0
14004016c:  mov    dword ptr [rbp + 0x2da8], edi
140040172:  movdqu xmmword ptr [rbp - 0x48], xmm0
140040177:  mov    qword ptr [rbp - 0x38], rdi
14004017b:  call   0x1408e8d60
140040180:  dec    eax
140040182:  cmp    eax, 0x40
140040185:  jae    0x140040198
140040187:  call   0x1408e8d60
14004018c:  dec    eax
14004018e:  cmp    eax, 1
140040191:  jae    0x140040198
140040193:  mov    edx, r12d
140040196:  jmp    0x1400401b3
140040198:  call   0x1408e8d60
14004019d:  dec    eax
14004019f:  cmp    eax, 0x40
1400401a2:  jae    0x1400401ae
1400401a4:  call   0x1408e8d60
1400401a9:  lea    edx, [rax - 1]
1400401ac:  jmp    0x1400401b3
1400401ae:  mov    edx, 0x40
1400401b3:  movss  xmm10, dword ptr [rip + 0xaafd84]
1400401bc:  mov    dword ptr [rbp - 0x30], edx
1400401bf:  nop    
1400401c0:  mov    rax, qword ptr [rbp - 0x10]
1400401c4:  mov    rsi, qword ptr [rbp - 0x40]
1400401c8:  mov    rbx, qword ptr [rbp - 0x48]
1400401cc:  cmp    qword ptr [rbp - 0x18], rax
1400401d0:  jne    0x1400401db
1400401d2:  cmp    rbx, rsi
1400401d5:  je     0x1400404ec
1400401db:  test   edi, edi
1400401dd:  jne    0x1400404f4
1400401e3:  movzx  eax, byte ptr [r13 + 0x1b0]
1400401eb:  test   al, al
1400401ed:  jne    0x14004068c
1400401f3:  mov    rcx, qword ptr [rbp - 0x18]
1400401f7:  cmp    rcx, qword ptr [rbp - 0x10]
1400401fb:  je     0x1400403c2
140040201:  movsxd r8, edx
140040204:  mov    qword ptr [rbp - 0x28], r8
140040208:  nop    dword ptr [rax + rax]
140040210:  mov    rdx, qword ptr [rbp - 0x40]
140040214:  mov    rbx, qword ptr [rbp - 0x48]
140040218:  mov    rax, rdx
14004021b:  sub    rax, rbx
14004021e:  sar    rax, 3
140040222:  cmp    rax, r8
140040225:  jae    0x1400403cc
14004022b:  mov    rdx, rcx
14004022e:  lea    rcx, [rbp + 0x970]
140040235:  call   0x140071d20
14004023a:  mov    r8, qword ptr [rbp - 0x18]
14004023e:  mov    rdx, qword ptr [rbp - 0x10]
140040242:  lea    rcx, [r8 + 0x20]
140040246:  call   0x14004e870
14004024b:  mov    rcx, qword ptr [rbp - 0x10]
14004024f:  add    rcx, -0x20
140040253:  call   0x140072260
140040258:  sub    qword ptr [rbp - 0x10], 0x20
14004025d:  mov    ecx, 0x2d0
140040262:  call   0x14052e6a0
140040267:  mov    rcx, rax
14004026a:  call   0x1402333c0
14004026f:  lea    r8, [rbp + 0x970]
140040276:  mov    rcx, rax
140040279:  lea    rdx, [rip + 0xa75730]   ; "resourcecompiler64.exe"
140040280:  mov    r15, rax
140040283:  mov    byte ptr [rax + 0x68], 1
140040287:  cmp    qword ptr [rbp + 0x988], 0xf
14004028f:  cmova  r8, qword ptr [rbp + 0x970]
140040297:  xor    r9d, r9d
14004029a:  call   0x1402334d0
14004029f:  mov    rcx, qword ptr [rbp - 0x38]
1400402a3:  mov    rsi, qword ptr [rbp - 0x40]
1400402a7:  cmp    rsi, rcx
1400402aa:  je     0x1400402b9
1400402ac:  mov    qword ptr [rsi], r15
1400402af:  add    qword ptr [rbp - 0x40], 8
1400402b4:  jmp    0x140040393
1400402b9:  mov    rdx, qword ptr [rbp - 0x48]
1400402bd:  mov    rdi, rsi
1400402c0:  sub    rdi, rdx
1400402c3:  movabs r8, 0x1fffffffffffffff
1400402cd:  mov    rax, rdi
1400402d0:  sar    rax, 3
1400402d4:  cmp    rax, r8
1400402d7:  je     0x140042428
1400402dd:  sub    rcx, rdx
1400402e0:  lea    r12, [rax + 1]
1400402e4:  sar    rcx, 3
1400402e8:  mov    rax, r8
1400402eb:  mov    rdx, rcx
1400402ee:  shr    rdx, 1
1400402f1:  sub    rax, rdx
1400402f4:  cmp    rcx, rax
1400402f7:  jbe    0x1400402fe
1400402f9:  mov    rcx, r8
1400402fc:  jmp    0x140040315
1400402fe:  lea    rax, [rdx + rcx]
140040302:  mov    rcx, r12
140040305:  cmp    rax, r12
140040308:  cmovae rcx, rax
14004030c:  cmp    rcx, r8
14004030f:  ja     0x14004242e
140040315:  lea    r13, [rcx*8]
14004031d:  mov    rcx, r13
140040320:  call   0x1400723e0
140040325:  and    rdi, 0xfffffffffffffff8
140040329:  mov    rbx, rax
14004032c:  mov    rcx, rax
14004032f:  mov    qword ptr [rax + rdi], r15
140040333:  mov    r8, qword ptr [rbp - 0x40]
140040337:  mov    rdx, qword ptr [rbp - 0x48]
14004033b:  cmp    rsi, r8
14004033e:  jne    0x140040345
140040340:  sub    r8, rdx
140040343:  jmp    0x140040361
140040345:  mov    r8, rsi
140040348:  sub    r8, rdx
14004034b:  call   0x1409d12d0
140040350:  mov    r8, qword ptr [rbp - 0x40]
140040354:  lea    rcx, [rdi + 8]
140040358:  sub    r8, rsi
14004035b:  mov    rdx, rsi
14004035e:  add    rcx, rbx
140040361:  call   0x1409d12d0
140040366:  mov    rdx, qword ptr [rbp - 0x48]
14004036a:  test   rdx, rdx
14004036d:  je     0x14004037f
14004036f:  mov    r8, qword ptr [rbp - 0x38]
140040373:  sub    r8, rdx
140040376:  sar    r8, 3
14004037a:  call   0x140056540
14004037f:  lea    rax, [rbx + r12*8]
140040383:  mov    qword ptr [rbp - 0x48], rbx
140040387:  mov    qword ptr [rbp - 0x40], rax
14004038b:  lea    rax, [rbx + r13]
14004038f:  mov    qword ptr [rbp - 0x38], rax
140040393:  lea    rcx, [rbp + 0x970]
14004039a:  call   0x140072260
14004039f:  mov    rcx, qword ptr [rbp - 0x18]
1400403a3:  mov    r8, qword ptr [rbp - 0x28]
1400403a7:  cmp    rcx, qword ptr [rbp - 0x10]
1400403ab:  jne    0x140040210
1400403b1:  mov    r15, qword ptr [rbp + 0x48]
1400403b5:  mov    edi, dword ptr [rbp + 0x2da8]
1400403bb:  mov    r13, qword ptr [rbp + 0x2d90]
1400403c2:  mov    rdx, qword ptr [rbp - 0x40]
1400403c6:  mov    rbx, qword ptr [rbp - 0x48]
1400403ca:  jmp    0x1400403dd
1400403cc:  mov    r15, qword ptr [rbp + 0x48]
1400403d0:  mov    edi, dword ptr [rbp + 0x2da8]
1400403d6:  mov    r13, qword ptr [rbp + 0x2d90]
1400403dd:  mov    dword ptr [rbp - 0x28], 0
1400403e4:  cmp    rbx, rdx
1400403e7:  je     0x1400404d9
1400403ed:  mov    r12d, 1
1400403f3:  nop    dword ptr [rax]
1400403f7:  nop    word ptr [rax + rax]
140040400:  mov    rcx, qword ptr [rbx]
140040403:  lea    r8, [rbp - 0x28]
140040407:  mov    edx, r12d
14004040a:  call   0x140234470
14004040f:  test   al, al
140040411:  je     0x1400404b0
140040417:  mov    eax, dword ptr [rbp - 0x28]
14004041a:  test   eax, eax
14004041c:  jne    0x1400404d1
140040422:  mov    rcx, qword ptr [rbx]
140040425:  test   rcx, rcx
140040428:  je     0x14004042f
14004042a:  call   0x140042560
14004042f:  mov    r8, rbx
140040432:  lea    rdx, [rbp + 0xbb0]
140040439:  lea    rcx, [rbp - 0x48]
14004043d:  call   0x140045b80
140040442:  addss  xmm9, xmm10
140040447:  lea    rdx, [rip + 0xa75062]   ; "ui_browse_mobile_transcoder_progress_converting_project"
14004044e:  lea    rcx, [rsp + 0x30]
140040453:  mov    rbx, qword ptr [rax]
140040456:  movaps xmm6, xmm9
14004045a:  divss  xmm6, xmm7
14004045e:  mulss  xmm6, xmm11
140040463:  addss  xmm6, xmm8
140040468:  call   0x140071970
14004046d:  comiss xmm12, xmm6
140040471:  mov    byte ptr [rsp + 0x50], 0
140040476:  jbe    0x140040480
140040478:  movss  dword ptr [rsp + 0x54], xmm6
14004047e:  jmp    0x140040488
140040480:  mov    dword ptr [rsp + 0x54], 0x3f7d70a4
140040488:  lea    rdx, [rsp + 0x30]
14004048d:  lea    rcx, [rbp + 0x2500]
140040494:  call   0x140042520
140040499:  mov    rdx, rax
14004049c:  mov    rcx, r15
14004049f:  call   0x1400472f0
1400404a4:  lea    rcx, [rsp + 0x30]
1400404a9:  call   0x140072260
1400404ae:  jmp    0x1400404b4
1400404b0:  add    rbx, 8
1400404b4:  cmp    rbx, qword ptr [rbp - 0x40]
1400404b8:  jne    0x140040400
1400404be:  mov    ecx, 0xa
1400404c3:  call   qword ptr [rip + 0x9a24c7]   ; KERNEL32.dll!Sleep
1400404c9:  mov    edx, dword ptr [rbp - 0x30]
1400404cc:  jmp    0x1400401c0
1400404d1:  mov    edi, eax
1400404d3:  mov    dword ptr [rbp + 0x2da8], eax
1400404d9:  mov    ecx, 0xa
1400404de:  call   qword ptr [rip + 0x9a24ac]   ; KERNEL32.dll!Sleep
1400404e4:  mov    edx, dword ptr [rbp - 0x30]
1400404e7:  jmp    0x1400401c0
1400404ec:  test   edi, edi
1400404ee:  je     0x14004068c
1400404f4:  cmp    rbx, rsi
1400404f7:  je     0x140040535
1400404f9:  nop    dword ptr [rax]
140040500:  mov    rdi, qword ptr [rbx]
140040503:  mov    rcx, qword ptr [rdi]
140040506:  cmp    rcx, -1
14004050a:  je     0x140040517
14004050c:  mov    edx, 0xffffffff
140040511:  call   qword ptr [rip + 0x9a1dd1]   ; KERNEL32.dll!TerminateProcess
140040517:  mov    rcx, rdi
14004051a:  call   0x140233440
14004051f:  mov    edx, 0x2d0
140040524:  mov    rcx, rdi
140040527:  call   0x14052e6f0
14004052c:  add    rbx, 8
140040530:  cmp    rbx, rsi
140040533:  jne    0x140040500
140040535:  xorps  xmm0, xmm0
140040538:  mov    qword ptr [rbp + 0x200], 0
140040543:  mov    ecx, 0x40
140040548:  mov    qword ptr [rbp + 0x208], 0
140040553:  movups xmmword ptr [rbp + 0x1f0], xmm0
14004055a:  call   0x1400723e0
14004055f:  movups xmm0, xmmword ptr [rip + 0xa75462]
140040566:  mov    qword ptr [rbp + 0x1f0], rax
14004056d:  lea    rdx, [rbp + 0x728]
140040574:  movups xmm1, xmmword ptr [rip + 0xa7545d]
14004057b:  mov    qword ptr [rbp + 0x200], 0x3b
140040586:  mov    rcx, r15
140040589:  mov    qword ptr [rbp + 0x208], 0x3f
140040594:  movups xmmword ptr [rax], xmm0
140040597:  movups xmm0, xmmword ptr [rip + 0xa7544a]
14004059e:  movups xmmword ptr [rax + 0x10], xmm1
1400405a2:  movups xmm1, xmmword ptr [rip + 0xa7544a]
1400405a9:  movups xmmword ptr [rax + 0x20], xmm0
1400405ad:  movss  xmm0, dword ptr [rip + 0xaafdbf]
1400405b5:  movups xmmword ptr [rax + 0x2b], xmm1
1400405b9:  mov    byte ptr [rax + 0x3b], 0
1400405bd:  mov    rax, qword ptr [rbp + 0x1f0]
1400405c4:  mov    qword ptr [rbp + 0x728], rax
1400405cb:  mov    rax, qword ptr [rbp + 0x1f8]
1400405d2:  mov    qword ptr [rbp + 0x730], rax
1400405d9:  mov    rax, qword ptr [rbp + 0x200]
1400405e0:  mov    qword ptr [rbp + 0x738], rax
1400405e7:  mov    rax, qword ptr [rbp + 0x208]
1400405ee:  mov    qword ptr [rbp + 0x740], rax
1400405f5:  movss  dword ptr [rbp + 0x214], xmm0
1400405fd:  movss  dword ptr [rbp + 0x74c], xmm0
140040605:  mov    byte ptr [rbp + 0x210], 1
14004060c:  mov    qword ptr [rbp + 0x200], 0
140040617:  mov    qword ptr [rbp + 0x208], 0xf
140040622:  mov    byte ptr [rbp + 0x1f0], 0
140040629:  mov    byte ptr [rbp + 0x748], 1
140040630:  call   0x1400472f0
140040635:  lea    rcx, [rbp + 0x1f0]
14004063c:  call   0x140072260
140040641:  mov    rcx, qword ptr [rbp - 0x48]
140040645:  test   rcx, rcx
140040648:  je     0x140042024
14004064e:  mov    rax, qword ptr [rbp - 0x38]
140040652:  sub    rax, rcx
140040655:  sar    rax, 3
140040659:  lea    rdx, [rax*8]
140040661:  cmp    rdx, 0x1000
140040668:  jb     0x140042009
14004066e:  mov    rax, qword ptr [rcx - 8]
140040672:  sub    rcx, rax
140040675:  sub    rcx, 8
140040679:  cmp    rcx, 0x1f
14004067d:  ja     0x14004215d
140040683:  add    rdx, 0x27
140040687:  jmp    0x14004200c
14004068c:  movzx  eax, byte ptr [r13 + 0x1b0]
140040694:  test   al, al
140040696:  je     0x1400406ea
140040698:  mov    rsi, qword ptr [rbp - 0x40]
14004069c:  mov    rbx, qword ptr [rbp - 0x48]
1400406a0:  cmp    rbx, rsi
1400406a3:  je     0x140040641
1400406a5:  nop    word ptr [rax + rax]
1400406b0:  mov    rdi, qword ptr [rbx]
1400406b3:  mov    rcx, qword ptr [rdi]
1400406b6:  cmp    rcx, -1
1400406ba:  je     0x1400406c7
1400406bc:  mov    edx, 0xffffffff
1400406c1:  call   qword ptr [rip + 0x9a1c21]   ; KERNEL32.dll!TerminateProcess
1400406c7:  mov    rcx, rdi
1400406ca:  call   0x140233440
1400406cf:  mov    edx, 0x2d0
1400406d4:  mov    rcx, rdi
1400406d7:  call   0x14052e6f0
1400406dc:  add    rbx, 8
1400406e0:  cmp    rbx, rsi
1400406e3:  jne    0x1400406b0
1400406e5:  jmp    0x140040641
1400406ea:  movzx  r12d, byte ptr [rbp + 0x2d98]
1400406f2:  xorps  xmm0, xmm0
1400406f5:  mov    qword ptr [rbp + 0x1a8], 0
140040700:  movdqu xmmword ptr [rbp + 0x198], xmm0
140040708:  test   r12b, r12b
14004070b:  jne    0x140041612
140040711:  lea    rdx, [rbp + 0x28]
140040715:  lea    rcx, [rbp + 0x14c0]
14004071c:  call   0x140071c40
140040721:  lea    rcx, [rbp + 0x14c0]
140040728:  call   0x14000e180
14004072d:  mov    rcx, rax
140040730:  lea    rdx, [rip + 0xa738d1]   ; "project.json"
140040737:  call   0x14000d880
14004073c:  mov    rdx, rax
14004073f:  lea    rcx, [rbp + 0x9b0]
140040746:  call   0x140071c40
14004074b:  lea    rcx, [rbp + 0x14c0]
140040752:  call   0x140071e80
140040757:  lea    rdx, [rbp + 0x58]
14004075b:  lea    rcx, [rbp + 0xe80]
140040762:  call   0x140071c40
140040767:  lea    rdx, [rip + 0xa7389a]   ; "project.json"
14004076e:  lea    rcx, [rbp + 0xe80]
140040775:  call   0x14000d880
14004077a:  mov    rdx, rax
14004077d:  lea    rcx, [rbp + 0x398]
140040784:  call   0x140071c40
140040789:  lea    rcx, [rbp + 0xe80]
140040790:  call   0x140071e80
140040795:  lea    rcx, [rbp + 0x9b0]
14004079c:  call   0x14000d7a0
1400407a1:  mov    rdx, rax
1400407a4:  lea    rcx, [rbp + 0xea0]
1400407ab:  call   0x1400496b0
1400407b0:  lea    rdx, [rbp + 0xea0]
1400407b7:  lea    rcx, [rbp + 0x21d0]
1400407be:  call   0x1404ce0d0
1400407c3:  lea    rcx, [rbp + 0xea0]
1400407ca:  call   0x140071e80
1400407cf:  lea    rcx, [rbp + 0x21d0]
1400407d6:  call   0x1404d0450
1400407db:  mov    rdx, rax
1400407de:  lea    rcx, [rbp + 0x5b8]
1400407e5:  call   0x14000d0f0
1400407ea:  lea    rdx, [rbp + 0x490]
1400407f1:  lea    rcx, [rbp + 0x5b8]
1400407f8:  call   0x14000dc30
1400407fd:  mov    edx, 7
140040802:  lea    rcx, [rsp + 0x30]
140040807:  call   0x140234fb0
14004080c:  cmp    qword ptr [rbp + 0x2f0], 0
140040814:  jne    0x140040942
14004081a:  cmp    byte ptr [rbp + 0x21f9], r12b
140040821:  je     0x1400408fe
140040827:  lea    rcx, [rbp + 0x5b8]
14004082e:  call   0x14000e5f0
140040833:  test   al, al
140040835:  je     0x1400408fe
14004083b:  mov    r8d, 2
140040841:  lea    rdx, [rbp + 0x398]
140040848:  lea    rcx, [rbp + 0x9b0]
14004084f:  call   0x14000e880
140040854:  test   al, al
140040856:  je     0x14004089d
140040858:  lea    rdx, [rbp + 0x58]
14004085c:  bts    r14d, 0xa
140040861:  lea    rcx, [rbp + 0xec0]
140040868:  call   0x140071c40
14004086d:  lea    rdx, [rbp + 0x490]
140040874:  lea    rcx, [rbp + 0xec0]
14004087b:  call   0x14000d850
140040880:  mov    rdx, rax
140040883:  lea    rcx, [rbp + 0x5b8]
14004088a:  mov    r8d, 2
140040890:  call   0x14000e880
140040895:  test   al, al
140040897:  je     0x14004089d
140040899:  xor    bl, bl
14004089b:  jmp    0x14004089f
14004089d:  mov    bl, 1
14004089f:  bt     r14d, 0xa
1400408a4:  jae    0x1400408b2
1400408a6:  lea    rcx, [rbp + 0xec0]
1400408ad:  call   0x140071e80
1400408b2:  test   bl, bl
1400408b4:  je     0x140040b07
1400408ba:  lea    rdx, [rip + 0xa74f7f]   ; "ui_browse_mobile_transcoder_error_failed_copying_project_file"
1400408c1:  lea    rcx, [rbp - 0x70]
1400408c5:  call   0x140071970
1400408ca:  lea    rdx, [rbp - 0x70]
1400408ce:  mov    byte ptr [rbp - 0x50], 1
1400408d2:  lea    rcx, [rbp + 0x2528]
1400408d9:  mov    dword ptr [rbp - 0x4c], 0xbf800000
1400408e0:  call   0x140042520
1400408e5:  mov    rdx, rax
1400408e8:  mov    rcx, r15
1400408eb:  call   0x1400472f0
1400408f0:  lea    rcx, [rbp - 0x70]
1400408f4:  call   0x140072260
1400408f9:  jmp    0x1400413a7
1400408fe:  lea    rdx, [rip + 0xa74f3b]   ; "ui_browse_mobile_transcoder_error_failed_copying_project_file"
140040905:  lea    rcx, [rbp - 0x70]
140040909:  call   0x140071970
14004090e:  lea    rdx, [rbp - 0x70]
140040912:  mov    byte ptr [rbp - 0x50], 1
140040916:  lea    rcx, [rbp + 0x2550]
14004091d:  mov    dword ptr [rbp - 0x4c], 0xbf800000
140040924:  call   0x140042520
140040929:  mov    rdx, rax
14004092c:  mov    rcx, r15
14004092f:  call   0x1400472f0
140040934:  lea    rcx, [rbp - 0x70]
140040938:  call   0x140072260
14004093d:  jmp    0x1400413a7
140040942:  lea    rcx, [rbp + 0x2e0]
140040949:  call   0x14000d7a0
14004094e:  mov    rdx, rax
140040951:  lea    rcx, [rbp + 0xee0]
140040958:  call   0x1400496b0
14004095d:  lea    rdx, [rbp + 0xee0]
140040964:  lea    rcx, [rbp + 0x2150]
14004096b:  call   0x1404ce0d0
140040970:  lea    rcx, [rbp + 0xee0]
140040977:  call   0x140071e80
14004097c:  cmp    byte ptr [rbp + 0x2179], 0
140040983:  jne    0x1400409a1
140040985:  lea    rdx, [rip + 0xa74eb4]   ; "ui_browse_mobile_transcoder_error_failed_copying_project_file"
14004098c:  lea    rcx, [rbp - 0x70]
140040990:  call   0x140071970
140040995:  lea    rcx, [rbp + 0x2578]
14004099c:  jmp    0x140040ac2
1400409a1:  xor    r8d, r8d
1400409a4:  lea    rdx, [rsp + 0x30]
1400409a9:  lea    rcx, [rbp + 0x2150]
1400409b0:  call   0x1404cfad0
1400409b5:  lea    rcx, [rbp + 0x2150]
1400409bc:  call   0x1404d0450
1400409c1:  mov    rdx, rax
1400409c4:  lea    rcx, [rbp + 0x1c90]
1400409cb:  call   0x14000d0f0
1400409d0:  mov    rcx, rax
1400409d3:  lea    rdx, [rbp + 0x1c70]
1400409da:  call   0x14000dc30
1400409df:  mov    rdx, rax
1400409e2:  lea    rcx, [rbp + 0x490]
1400409e9:  call   0x140071ba0
1400409ee:  lea    rcx, [rbp + 0x1c70]
1400409f5:  call   0x140071e80
1400409fa:  lea    rcx, [rbp + 0x1c90]
140040a01:  call   0x140071e80
140040a06:  mov    r8d, 2
140040a0c:  lea    rdx, [rbp + 0x398]
140040a13:  lea    rcx, [rbp + 0x280]
140040a1a:  call   0x14000e880
140040a1f:  test   al, al
140040a21:  je     0x140040a82
140040a23:  lea    rdx, [rbp + 0x58]
140040a27:  lea    rcx, [rbp + 0xf00]
140040a2e:  call   0x140071c40
140040a33:  lea    rdx, [rbp + 0x490]
140040a3a:  lea    rcx, [rbp + 0xf00]
140040a41:  call   0x14000d850
140040a46:  lea    rcx, [rbp + 0x2150]
140040a4d:  mov    rbx, rax
140040a50:  call   0x1404d0450
140040a55:  mov    rdx, rax
140040a58:  lea    rcx, [rbp - 0x70]
140040a5c:  call   0x14000d0f0
140040a61:  mov    r8d, 2
140040a67:  lea    rcx, [rbp - 0x70]
140040a6b:  mov    rdx, rbx
140040a6e:  or     r14d, 0x1800
140040a75:  call   0x14000e880
140040a7a:  test   al, al
140040a7c:  je     0x140040a82
140040a7e:  xor    bl, bl
140040a80:  jmp    0x140040a84
140040a82:  mov    bl, 1
140040a84:  bt     r14d, 0xc
140040a89:  jae    0x140040a94
140040a8b:  lea    rcx, [rbp - 0x70]
140040a8f:  call   0x140071e80
140040a94:  bt     r14d, 0xb
140040a99:  jae    0x140040aa7
140040a9b:  lea    rcx, [rbp + 0xf00]
140040aa2:  call   0x140071e80
140040aa7:  test   bl, bl
140040aa9:  je     0x140040afb
140040aab:  lea    rdx, [rip + 0xa74d8e]   ; "ui_browse_mobile_transcoder_error_failed_copying_project_file"
140040ab2:  lea    rcx, [rbp - 0x70]
140040ab6:  call   0x140071970
140040abb:  lea    rcx, [rbp + 0x25a0]
140040ac2:  lea    rdx, [rbp - 0x70]
140040ac6:  mov    byte ptr [rbp - 0x50], 1
140040aca:  mov    dword ptr [rbp - 0x4c], 0xbf800000
140040ad1:  call   0x140042520
140040ad6:  mov    rdx, rax
140040ad9:  mov    rcx, r15
140040adc:  call   0x1400472f0
140040ae1:  lea    rcx, [rbp - 0x70]
140040ae5:  call   0x140072260
140040aea:  lea    rcx, [rbp + 0x2150]
140040af1:  call   0x14001bdd0
140040af6:  jmp    0x1400413a7
140040afb:  lea    rcx, [rbp + 0x2150]
140040b02:  call   0x14001bdd0
140040b07:  cmp    byte ptr [r13 + 0xc8], 7
140040b0f:  jne    0x140040c39
140040b15:  lea    rdx, [rbp + 0x7b8]
140040b1c:  lea    rcx, [r13 + 0xc0]
140040b23:  call   0x140237bb0
140040b28:  mov    rsi, qword ptr [rbp + 0x7c0]
140040b2f:  mov    rdi, qword ptr [rbp + 0x7b8]
140040b36:  cmp    rdi, rsi
140040b39:  je     0x140040c2d
140040b3f:  nop    
140040b40:  mov    rdx, rdi
140040b43:  lea    rcx, [rbp + 0x810]
140040b4a:  call   0x140071d20
140040b4f:  lea    rdx, [rbp + 0x20a0]
140040b56:  lea    rcx, [rbp + 0x810]
140040b5d:  call   0x140069460
140040b62:  lea    rdx, [rsp + 0x60]
140040b67:  lea    rcx, [r13 + 0xc0]
140040b6e:  movups xmm0, xmmword ptr [rax]
140040b71:  movaps xmmword ptr [rsp + 0x60], xmm0
140040b76:  call   0x1402372e0
140040b7b:  mov    rbx, rax
140040b7e:  cmp    byte ptr [rax + 8], 7
140040b82:  jne    0x140040c14
140040b88:  lea    rdx, [rip + 0xa71661]   ; "value"
140040b8f:  lea    rcx, [rbp + 0x2080]
140040b96:  call   0x140070ce0
140040b9b:  lea    rdx, [rsp + 0x60]
140040ba0:  mov    rcx, rbx
140040ba3:  movups xmm0, xmmword ptr [rax]
140040ba6:  movaps xmmword ptr [rsp + 0x60], xmm0
140040bab:  call   0x140237b90
140040bb0:  test   al, al
140040bb2:  je     0x140040c14
140040bb4:  lea    rdx, [rip + 0xa71635]   ; "value"
140040bbb:  lea    rcx, [rbp + 0x2070]
140040bc2:  call   0x140070ce0
140040bc7:  lea    rdx, [rsp + 0x60]
140040bcc:  mov    rcx, rbx
140040bcf:  movups xmm0, xmmword ptr [rax]
140040bd2:  movaps xmmword ptr [rsp + 0x60], xmm0
140040bd7:  call   0x1402372e0
140040bdc:  lea    rdx, [rbp + 0x2060]
140040be3:  mov    rbx, rax
140040be6:  lea    rcx, [rbp + 0x810]
140040bed:  call   0x140069460
140040bf2:  lea    rdx, [rsp + 0x60]
140040bf7:  lea    rcx, [rsp + 0x30]
140040bfc:  movups xmm0, xmmword ptr [rax]
140040bff:  movaps xmmword ptr [rsp + 0x60], xmm0
140040c04:  call   0x1402372e0
140040c09:  mov    rcx, rax
140040c0c:  mov    rdx, rbx
140040c0f:  call   0x1402353c0
140040c14:  lea    rcx, [rbp + 0x810]
140040c1b:  call   0x140072260
140040c20:  add    rdi, 0x20
140040c24:  cmp    rdi, rsi
140040c27:  jne    0x140040b40
140040c2d:  lea    rcx, [rbp + 0x7b8]
140040c34:  call   0x14004ffe0
140040c39:  xor    edx, edx
140040c3b:  lea    rcx, [rbp + 0x3f8]
140040c42:  call   0x140234fb0
140040c47:  lea    rcx, [rbp + 0x398]
140040c4e:  call   0x14000d7a0
140040c53:  mov    rdx, rax
140040c56:  lea    rcx, [rbp + 0xae8]
140040c5d:  call   0x140014570
140040c62:  xor    r8d, r8d
140040c65:  lea    rdx, [rbp + 0x3f8]
140040c6c:  lea    rcx, [rbp + 0xae8]
140040c73:  call   0x14000fcb0
140040c78:  test   al, al
140040c7a:  je     0x1400413f5
140040c80:  lea    rdx, [rbp + 0x1cb0]
140040c87:  lea    rcx, [rbp + 0x490]
140040c8e:  call   0x14000dab0
140040c93:  mov    rdx, rax
140040c96:  lea    rcx, [rbp]
140040c9a:  call   0x140235210
140040c9f:  lea    rdx, [rip + 0xa7156a]   ; "preview"
140040ca6:  lea    rcx, [rbp + 0x2050]
140040cad:  call   0x140070ce0
140040cb2:  lea    rdx, [rsp + 0x60]
140040cb7:  lea    rcx, [rbp + 0x3f8]
140040cbe:  movups xmm0, xmmword ptr [rax]
140040cc1:  movaps xmmword ptr [rsp + 0x60], xmm0
140040cc6:  call   0x1402372e0
140040ccb:  mov    rdx, rax
140040cce:  lea    rcx, [rbp]
140040cd2:  call   0x140235460
140040cd7:  lea    rcx, [rbp]
140040cdb:  call   0x140235370
140040ce0:  lea    rcx, [rbp + 0x1cb0]
140040ce7:  call   0x140072260
140040cec:  lea    rdx, [rip + 0xa7346d]   ; "general"
140040cf3:  lea    rcx, [rbp + 0x2040]
140040cfa:  call   0x140070ce0
140040cff:  lea    rdx, [rsp + 0x60]
140040d04:  lea    rcx, [rbp + 0x3f8]
140040d0b:  movups xmm0, xmmword ptr [rax]
140040d0e:  movaps xmmword ptr [rsp + 0x60], xmm0
140040d13:  call   0x140237b90
140040d18:  test   al, al
140040d1a:  je     0x14004130c
140040d20:  lea    rdx, [rip + 0xa73439]   ; "general"
140040d27:  lea    rcx, [rbp + 0x2030]
140040d2e:  call   0x140070ce0
140040d33:  lea    rdx, [rsp + 0x60]
140040d38:  lea    rcx, [rbp + 0x3f8]
140040d3f:  movups xmm0, xmmword ptr [rax]
140040d42:  movaps xmmword ptr [rsp + 0x60], xmm0
140040d47:  call   0x1402372e0
140040d4c:  lea    rdx, [rip + 0xa7321d]   ; "properties"
140040d53:  mov    rbx, rax
140040d56:  lea    rcx, [rbp + 0x2020]
140040d5d:  call   0x140070ce0
140040d62:  lea    rdx, [rsp + 0x60]
140040d67:  mov    rcx, rbx
140040d6a:  movups xmm0, xmmword ptr [rax]
140040d6d:  movaps xmmword ptr [rsp + 0x60], xmm0
140040d72:  call   0x140237b90
140040d77:  test   al, al
140040d79:  je     0x14004130c
140040d7f:  lea    rdx, [rip + 0xa733da]   ; "general"
140040d86:  lea    rcx, [rbp + 0x2010]
140040d8d:  call   0x140070ce0
140040d92:  lea    rdx, [rsp + 0x60]
140040d97:  lea    rcx, [rbp + 0x3f8]
140040d9e:  movups xmm0, xmmword ptr [rax]
140040da1:  movaps xmmword ptr [rsp + 0x60], xmm0
140040da6:  call   0x1402372e0
140040dab:  lea    rdx, [rip + 0xa731be]   ; "properties"
140040db2:  mov    rbx, rax
140040db5:  lea    rcx, [rbp + 0x2000]
140040dbc:  call   0x140070ce0
140040dc1:  lea    rdx, [rsp + 0x60]
140040dc6:  mov    rcx, rbx
140040dc9:  movups xmm0, xmmword ptr [rax]
140040dcc:  movaps xmmword ptr [rsp + 0x60], xmm0
140040dd1:  call   0x1402372e0
140040dd6:  mov    r14, rax
140040dd9:  cmp    byte ptr [rax + 8], 7
140040ddd:  jne    0x14004130c
140040de3:  lea    rdx, [rbp + 0x7a0]
140040dea:  lea    rcx, [rsp + 0x30]
140040def:  call   0x140237bb0
140040df4:  mov    r12, qword ptr [rbp + 0x7a8]
140040dfb:  mov    rdi, qword ptr [rbp + 0x7a0]
140040e02:  cmp    rdi, r12
140040e05:  je     0x1400412f8
140040e0b:  nop    dword ptr [rax + rax]
140040e10:  mov    rdx, rdi
140040e13:  lea    rcx, [rbp + 0x300]
140040e1a:  call   0x140071d20
140040e1f:  lea    rdx, [rbp + 0x1ef0]
140040e26:  lea    rcx, [rbp + 0x300]
140040e2d:  call   0x140069460
140040e32:  lea    rdx, [rsp + 0x60]
140040e37:  lea    rcx, [rsp + 0x30]
140040e3c:  movups xmm0, xmmword ptr [rax]
140040e3f:  movaps xmmword ptr [rsp + 0x60], xmm0
140040e44:  call   0x1402372e0
140040e49:  cmp    byte ptr [rax + 8], 0
140040e4d:  je     0x1400412db
140040e53:  lea    rdx, [rbp + 0x1ff0]
140040e5a:  lea    rcx, [rbp + 0x300]
140040e61:  call   0x140069460
140040e66:  lea    rdx, [rsp + 0x60]
140040e6b:  mov    rcx, r14
140040e6e:  movups xmm0, xmmword ptr [rax]
140040e71:  movaps xmmword ptr [rsp + 0x60], xmm0
140040e76:  call   0x140237b90
140040e7b:  test   al, al
140040e7d:  je     0x14004122f
140040e83:  lea    rdx, [rbp + 0x1fe0]
140040e8a:  lea    rcx, [rbp + 0x300]
140040e91:  call   0x140069460
140040e96:  lea    rdx, [rsp + 0x60]
140040e9b:  mov    rcx, r14
140040e9e:  movups xmm0, xmmword ptr [rax]
140040ea1:  movaps xmmword ptr [rsp + 0x60], xmm0
140040ea6:  call   0x1402372e0
140040eab:  lea    rdx, [rbp + 0x1fd0]
140040eb2:  mov    rsi, rax
140040eb5:  lea    rcx, [rbp + 0x300]
140040ebc:  call   0x140069460
140040ec1:  lea    rdx, [rsp + 0x60]
140040ec6:  lea    rcx, [rsp + 0x30]
140040ecb:  movups xmm0, xmmword ptr [rax]
140040ece:  movaps xmmword ptr [rsp + 0x60], xmm0
140040ed3:  call   0x1402372e0
140040ed8:  lea    rdx, [rip + 0xa71311]   ; "value"
140040edf:  mov    r15, rax
140040ee2:  lea    rcx, [rbp + 0x1fc0]
140040ee9:  call   0x140070ce0
140040eee:  lea    rdx, [rsp + 0x60]
140040ef3:  mov    rcx, rsi
140040ef6:  movups xmm0, xmmword ptr [rax]
140040ef9:  movaps xmmword ptr [rsp + 0x60], xmm0
140040efe:  call   0x1402372e0
140040f03:  mov    rcx, rax
140040f06:  mov    rdx, r15
140040f09:  call   0x1402353c0
140040f0e:  lea    rdx, [rip + 0xa72c8b]   ; "type"
140040f15:  lea    rcx, [rbp + 0x1fb0]
140040f1c:  call   0x140070ce0
140040f21:  lea    rdx, [rsp + 0x60]
140040f26:  mov    rcx, rsi
140040f29:  movups xmm0, xmmword ptr [rax]
140040f2c:  movaps xmmword ptr [rsp + 0x60], xmm0
140040f31:  call   0x140237b90
140040f36:  test   al, al
140040f38:  je     0x1400412db
140040f3e:  lea    rdx, [rip + 0xa72c5b]   ; "type"
140040f45:  lea    rcx, [rbp + 0x1fa0]
140040f4c:  call   0x140070ce0
140040f51:  lea    rdx, [rsp + 0x60]
140040f56:  mov    rcx, rsi
140040f59:  movups xmm0, xmmword ptr [rax]
140040f5c:  movaps xmmword ptr [rsp + 0x60], xmm0
140040f61:  call   0x1402372e0
140040f66:  cmp    byte ptr [rax + 8], 4
140040f6a:  jne    0x1400412db
140040f70:  cmp    byte ptr [r15 + 8], 4
140040f75:  jne    0x1400412db
140040f7b:  lea    rdx, [rip + 0xa72c1e]   ; "type"
140040f82:  lea    rcx, [rbp + 0x1f90]
140040f89:  call   0x140070ce0
140040f8e:  lea    rdx, [rsp + 0x60]
140040f93:  mov    rcx, rsi
140040f96:  movups xmm0, xmmword ptr [rax]
140040f99:  movaps xmmword ptr [rsp + 0x60], xmm0
140040f9e:  call   0x1402372e0
140040fa3:  mov    rcx, rax
140040fa6:  lea    rdx, [rbp - 0x70]
140040faa:  call   0x1402357d0
140040faf:  lea    rdx, [rip + 0xa72c42]   ; "scenetexture"
140040fb6:  lea    rcx, [rbp - 0x70]
140040fba:  call   0x140042480
140040fbf:  test   al, al
140040fc1:  je     0x140041221
140040fc7:  lea    rdx, [rbp + 0x2e0]
140040fce:  lea    rcx, [rbp + 0xf20]
140040fd5:  call   0x140071c40
140040fda:  lea    rcx, [rbp + 0xf20]
140040fe1:  call   0x14000e180
140040fe6:  lea    rdx, [rbp + 0x1cd0]
140040fed:  mov    rcx, r15
140040ff0:  mov    rbx, rax
140040ff3:  call   0x1402357d0
140040ff8:  mov    rdx, rax
140040ffb:  mov    rcx, rbx
140040ffe:  call   0x14000da30
140041003:  mov    rdx, rax
140041006:  lea    rcx, [rbp + 0x5d8]
14004100d:  call   0x140071c40
140041012:  lea    rcx, [rbp + 0x1cd0]
140041019:  call   0x140072260
14004101e:  lea    rcx, [rbp + 0xf20]
140041025:  call   0x140071e80
14004102a:  lea    rcx, [rbp + 0x5d8]
140041031:  call   0x14000e6a0
140041036:  test   al, al
140041038:  je     0x1400411ed
14004103e:  lea    rdx, [rbp + 0x58]
140041042:  lea    rcx, [rbp + 0xf40]
140041049:  call   0x140071c40
14004104e:  lea    rdx, [rip + 0xa71bcf]   ; "files"
140041055:  lea    rcx, [rbp + 0xf40]
14004105c:  call   0x14000d880
140041061:  mov    rdx, rax
140041064:  lea    rcx, [rbp + 0x598]
14004106b:  call   0x140071c40
140041070:  lea    rcx, [rbp + 0xf40]
140041077:  call   0x140071e80
14004107c:  lea    rcx, [rbp + 0x598]
140041083:  call   0x14000eb20
140041088:  lea    rdx, [rbp + 0x1cf0]
14004108f:  lea    rcx, [rbp + 0x5d8]
140041096:  call   0x14000dc30
14004109b:  mov    rdx, rax
14004109e:  lea    rcx, [rbp + 0x598]
1400410a5:  call   0x14000d850
1400410aa:  lea    rcx, [rbp + 0x1cf0]
1400410b1:  call   0x140071e80
1400410b6:  lea    r8, [rbp + 0x78]
1400410ba:  lea    rdx, [rbp + 0x598]
1400410c1:  lea    rcx, [rbp + 0x5d8]
1400410c8:  call   0x14000e9c0
1400410cd:  lea    rdx, [rip + 0xa71b50]   ; "files"
1400410d4:  lea    rcx, [rbp + 0x1d90]
1400410db:  call   0x14000d0f0
1400410e0:  lea    rdx, [rbp + 0x1d70]
1400410e7:  mov    rbx, rax
1400410ea:  lea    rcx, [rbp + 0x5d8]
1400410f1:  call   0x14000dc30
1400410f6:  mov    rdx, rax
1400410f9:  mov    rcx, rbx
1400410fc:  call   0x14000d850
140041101:  mov    rcx, rax
140041104:  lea    rdx, [rbp + 0x1d50]
14004110b:  call   0x14000dab0
140041110:  mov    rdx, rax
140041113:  lea    rcx, [rbp + 0x1d30]
14004111a:  mov    r8b, 0x2f
14004111d:  call   0x14000c5b0
140041122:  mov    r8, rax
140041125:  lea    rdx, [rip + 0xa748dc]   ; "MPKG_VFS://"
14004112c:  lea    rcx, [rbp + 0x1d10]
140041133:  call   0x140042e90
140041138:  mov    rdx, rax
14004113b:  lea    rcx, [rbp + 0x700]
140041142:  call   0x140235210
140041147:  lea    rdx, [rip + 0xa710a2]   ; "value"
14004114e:  lea    rcx, [rbp + 0x1f80]
140041155:  call   0x140070ce0
14004115a:  lea    rdx, [rsp + 0x60]
14004115f:  mov    rcx, rsi
140041162:  movups xmm0, xmmword ptr [rax]
140041165:  movaps xmmword ptr [rsp + 0x60], xmm0
14004116a:  call   0x1402372e0
14004116f:  mov    rdx, rax
140041172:  lea    rcx, [rbp + 0x700]
140041179:  call   0x140235460
14004117e:  lea    rcx, [rbp + 0x700]
140041185:  call   0x140235370
14004118a:  lea    rcx, [rbp + 0x1d10]
140041191:  call   0x140072260
140041196:  lea    rcx, [rbp + 0x1d30]
14004119d:  call   0x140072260
1400411a2:  lea    rcx, [rbp + 0x1d50]
1400411a9:  call   0x140072260
1400411ae:  lea    rcx, [rbp + 0x1d70]
1400411b5:  call   0x140071e80
1400411ba:  lea    rcx, [rbp + 0x1d90]
1400411c1:  call   0x140071e80
1400411c6:  cmp    dword ptr [rbp + 0x78], 0
1400411ca:  jne    0x1400411df
1400411cc:  lea    rdx, [rbp + 0x598]
1400411d3:  lea    rcx, [rbp + 0x198]
1400411da:  call   0x140045930
1400411df:  lea    rcx, [rbp + 0x598]
1400411e6:  call   0x140071e80
1400411eb:  jmp    0x140041215
1400411ed:  lea    rdx, [rbp + 0x1f70]
1400411f4:  lea    rcx, [rbp + 0x300]
1400411fb:  call   0x140069460
140041200:  lea    rdx, [rsp + 0x60]
140041205:  mov    rcx, r14
140041208:  movups xmm0, xmmword ptr [rax]
14004120b:  movaps xmmword ptr [rsp + 0x60], xmm0
140041210:  call   0x140237760
140041215:  lea    rcx, [rbp + 0x5d8]
14004121c:  call   0x140071e80
140041221:  lea    rcx, [rbp - 0x70]
140041225:  call   0x140072260
14004122a:  jmp    0x1400412db
14004122f:  mov    edx, 7
140041234:  lea    rcx, [rbp]
140041238:  call   0x140234fb0
14004123d:  lea    rdx, [rbp + 0x1f60]
140041244:  lea    rcx, [rbp + 0x300]
14004124b:  call   0x140069460
140041250:  lea    rdx, [rsp + 0x60]
140041255:  lea    rcx, [rsp + 0x30]
14004125a:  movups xmm0, xmmword ptr [rax]
14004125d:  movaps xmmword ptr [rsp + 0x60], xmm0
140041262:  call   0x1402372e0
140041267:  lea    rdx, [rip + 0xa70f82]   ; "value"
14004126e:  mov    rbx, rax
140041271:  lea    rcx, [rbp + 0x1f50]
140041278:  call   0x140070ce0
14004127d:  lea    rdx, [rsp + 0x60]
140041282:  lea    rcx, [rbp]
140041286:  movups xmm0, xmmword ptr [rax]
140041289:  movaps xmmword ptr [rsp + 0x60], xmm0
14004128e:  call   0x1402372e0
140041293:  mov    rcx, rax
140041296:  mov    rdx, rbx
140041299:  call   0x1402353c0
14004129e:  lea    rdx, [rbp + 0x1f40]
1400412a5:  lea    rcx, [rbp + 0x300]
1400412ac:  call   0x140069460
1400412b1:  lea    rdx, [rsp + 0x60]
1400412b6:  mov    rcx, r14
1400412b9:  movups xmm0, xmmword ptr [rax]
1400412bc:  movaps xmmword ptr [rsp + 0x60], xmm0
1400412c1:  call   0x1402372e0
1400412c6:  mov    rcx, rax
1400412c9:  lea    rdx, [rbp]
1400412cd:  call   0x1402353c0
1400412d2:  lea    rcx, [rbp]
1400412d6:  call   0x140235370
1400412db:  lea    rcx, [rbp + 0x300]
1400412e2:  call   0x140072260
1400412e7:  add    rdi, 0x20
1400412eb:  cmp    rdi, r12
1400412ee:  jne    0x140040e10
1400412f4:  mov    r15, qword ptr [rbp + 0x48]
1400412f8:  lea    rcx, [rbp + 0x7a0]
1400412ff:  call   0x14004ffe0
140041304:  movzx  r12d, byte ptr [rbp + 0x2d98]
14004130c:  mov    r8b, 1
14004130f:  lea    rdx, [rbp + 0x3f8]
140041316:  lea    rcx, [rbp + 0x1db0]
14004131d:  call   0x14000efa0
140041322:  lea    rcx, [rbp + 0x398]
140041329:  mov    rdx, rax
14004132c:  call   0x14000d7a0
140041331:  mov    rcx, rax
140041334:  call   0x140014830
140041339:  lea    rcx, [rbp + 0x1db0]
140041340:  movzx  ebx, al
140041343:  call   0x140072260
140041348:  test   bl, bl
14004134a:  jne    0x1400413f5
140041350:  lea    rdx, [rip + 0xa744e9]   ; "ui_browse_mobile_transcoder_error_failed_copying_project_file"
140041357:  lea    rcx, [rbp - 0x70]
14004135b:  call   0x140071970
140041360:  lea    rdx, [rbp - 0x70]
140041364:  mov    byte ptr [rbp - 0x50], 1
140041368:  lea    rcx, [rbp + 0x25c8]
14004136f:  mov    dword ptr [rbp - 0x4c], 0xbf800000
140041376:  call   0x140042520
14004137b:  mov    rdx, rax
14004137e:  mov    rcx, r15
140041381:  call   0x1400472f0
140041386:  lea    rcx, [rbp - 0x70]
14004138a:  call   0x140072260
14004138f:  lea    rcx, [rbp + 0xae8]
140041396:  call   0x140072260
14004139b:  lea    rcx, [rbp + 0x3f8]
1400413a2:  call   0x140235370
1400413a7:  lea    rcx, [rsp + 0x30]
1400413ac:  call   0x140235370
1400413b1:  lea    rcx, [rbp + 0x490]
1400413b8:  call   0x140071e80
1400413bd:  lea    rcx, [rbp + 0x5b8]
1400413c4:  call   0x140071e80
1400413c9:  lea    rcx, [rbp + 0x21d0]
1400413d0:  call   0x14001bdd0
1400413d5:  lea    rcx, [rbp + 0x398]
1400413dc:  call   0x140071e80
1400413e1:  lea    rcx, [rbp + 0x9b0]
1400413e8:  call   0x140071e80
1400413ed:  xor    r14d, r14d
1400413f0:  jmp    0x140041f7f
1400413f5:  lea    rcx, [rbp + 0x398]
1400413fc:  call   0x14000d7a0
140041401:  mov    rdx, rax
140041404:  lea    rcx, [rbp + 0xf60]
14004140b:  call   0x1400496b0
140041410:  lea    rdx, [rbp + 0xf60]
140041417:  lea    rcx, [rbp + 0x2880]
14004141e:  call   0x1404ce0d0
140041423:  mov    rdx, rax
140041426:  lea    rcx, [rbp + 0x21d0]
14004142d:  call   0x14002a3d0
140041432:  lea    rcx, [rbp + 0x2880]
140041439:  call   0x14001bdd0
14004143e:  lea    rcx, [rbp + 0xf60]
140041445:  call   0x140071e80
14004144a:  lea    rcx, [rbp + 0x21d0]
140041451:  call   0x1404d0400
140041456:  mov    rdx, rax
140041459:  lea    rcx, [rbp + 0x7f0]
140041460:  call   0x14000d0f0
140041465:  cmp    dword ptr [r13 + 0x64], 1
14004146a:  jle    0x1400415a8
140041470:  lea    rcx, [rbp + 0x7f0]
140041477:  call   0x14000e5f0
14004147c:  test   al, al
14004147e:  je     0x1400415a8
140041484:  lea    rcx, [rbp + 0x7f0]
14004148b:  call   0x14000d7a0
140041490:  mov    rdx, rax
140041493:  lea    rcx, [rbp + 0xf80]
14004149a:  call   0x140014570
14004149f:  xor    edx, edx
1400414a1:  lea    rcx, [rbp + 0x9f8]
1400414a8:  call   0x140234fb0
1400414ad:  xor    r8d, r8d
1400414b0:  lea    rdx, [rbp + 0x9f8]
1400414b7:  lea    rcx, [rbp + 0xf80]
1400414be:  call   0x14000fcb0
1400414c3:  test   al, al
1400414c5:  je     0x140041590
1400414cb:  cmp    byte ptr [rbp + 0xa00], 7
1400414d2:  jne    0x140041590
1400414d8:  lea    rdx, [rip + 0xa72c81]   ; "general"
1400414df:  lea    rcx, [rbp + 0x1f30]
1400414e6:  call   0x140070ce0
1400414eb:  lea    rdx, [rsp + 0x60]
1400414f0:  lea    rcx, [rbp + 0x9f8]
1400414f7:  movups xmm0, xmmword ptr [rax]
1400414fa:  movaps xmmword ptr [rsp + 0x60], xmm0
1400414ff:  call   0x1402372e0
140041504:  mov    rbx, rax
140041507:  cmp    byte ptr [rax + 8], 7
14004150b:  jne    0x140041557
14004150d:  mov    edx, dword ptr [r13 + 0x64]
140041511:  lea    rcx, [rbp]
140041515:  call   0x140235110
14004151a:  lea    rdx, [rip + 0xa744f7]   ; "texturereduction"
140041521:  lea    rcx, [rbp + 0x1f20]
140041528:  call   0x140070ce0
14004152d:  lea    rdx, [rsp + 0x60]
140041532:  mov    rcx, rbx
140041535:  movups xmm0, xmmword ptr [rax]
140041538:  movaps xmmword ptr [rsp + 0x60], xmm0
14004153d:  call   0x1402372e0
140041542:  mov    rdx, rax
140041545:  lea    rcx, [rbp]
140041549:  call   0x140235460
14004154e:  lea    rcx, [rbp]
140041552:  call   0x140235370
140041557:  mov    r8b, 1
14004155a:  lea    rdx, [rbp + 0x9f8]
140041561:  lea    rcx, [rbp + 0x1dd0]
140041568:  call   0x14000efa0
14004156d:  lea    rcx, [rbp + 0x7f0]
140041574:  mov    rdx, rax
140041577:  call   0x14000d7a0
14004157c:  mov    rcx, rax
14004157f:  call   0x140014830
140041584:  lea    rcx, [rbp + 0x1dd0]
14004158b:  call   0x140072260
140041590:  lea    rcx, [rbp + 0x9f8]
140041597:  call   0x140235370
14004159c:  lea    rcx, [rbp + 0xf80]
1400415a3:  call   0x140072260
1400415a8:  lea    rcx, [rbp + 0x7f0]
1400415af:  call   0x140071e80
1400415b4:  lea    rcx, [rbp + 0xae8]
1400415bb:  call   0x140072260
1400415c0:  lea    rcx, [rbp + 0x3f8]
1400415c7:  call   0x140235370
1400415cc:  lea    rcx, [rsp + 0x30]
1400415d1:  call   0x140235370
1400415d6:  lea    rcx, [rbp + 0x490]
1400415dd:  call   0x140071e80
1400415e2:  lea    rcx, [rbp + 0x5b8]
1400415e9:  call   0x140071e80
1400415ee:  lea    rcx, [rbp + 0x21d0]
1400415f5:  call   0x14001bdd0
1400415fa:  lea    rcx, [rbp + 0x398]
140041601:  call   0x140071e80
140041606:  lea    rcx, [rbp + 0x9b0]
14004160d:  call   0x140071e80
140041612:  lea    rdx, [rbp + 0x58]
140041616:  lea    rcx, [rbp + 0xfa0]
14004161d:  call   0x140071c40
140041622:  lea    rdx, [rip + 0xa729df]   ; "project.json"
140041629:  lea    rcx, [rbp + 0xfa0]
140041630:  call   0x14000d880
140041635:  mov    rdx, rax
140041638:  lea    rcx, [rbp + 0xac8]
14004163f:  call   0x140071c40
140041644:  lea    rcx, [rbp + 0xfa0]
14004164b:  call   0x140071e80
140041650:  lea    rcx, [rbp + 0xac8]
140041657:  call   0x14000d7a0
14004165c:  mov    rdx, rax
14004165f:  lea    rcx, [rbp + 0x1020]
140041666:  call   0x140014570
14004166b:  xor    edx, edx
14004166d:  lea    rcx, [rbp + 0x3d0]
140041674:  call   0x140234fb0
140041679:  xor    r8d, r8d
14004167c:  lea    rdx, [rbp + 0x3d0]
140041683:  lea    rcx, [rbp + 0x1020]
14004168a:  call   0x14000fcb0
14004168f:  test   al, al
140041691:  je     0x14004178d
140041697:  cmp    byte ptr [rbp + 0x3d8], 7
14004169e:  jne    0x14004178d
1400416a4:  cmp    qword ptr [r13 + 0x50], 0
1400416a9:  je     0x1400416f9
1400416ab:  lea    rdx, [r13 + 0x40]
1400416af:  lea    rcx, [rbp]
1400416b3:  call   0x140235210
1400416b8:  lea    rdx, [rip + 0xa74371]   ; "workshopurl"
1400416bf:  lea    rcx, [rbp + 0x1f10]
1400416c6:  call   0x140070ce0
1400416cb:  lea    rdx, [rsp + 0x60]
1400416d0:  lea    rcx, [rbp + 0x3d0]
1400416d7:  movups xmm0, xmmword ptr [rax]
1400416da:  movaps xmmword ptr [rsp + 0x60], xmm0
1400416df:  call   0x1402372e0
1400416e4:  mov    rdx, rax
1400416e7:  lea    rcx, [rbp]
1400416eb:  call   0x140235460
1400416f0:  lea    rcx, [rbp]
1400416f4:  call   0x140235370
1400416f9:  cmp    qword ptr [rbp + 0x2b0], 0
140041701:  je     0x140041754
140041703:  lea    rdx, [rbp + 0x2a0]
14004170a:  lea    rcx, [rbp]
14004170e:  call   0x140235210
140041713:  lea    rdx, [rip + 0xa70aea]   ; "file"
14004171a:  lea    rcx, [rbp + 0x1f00]
140041721:  call   0x140070ce0
140041726:  lea    rdx, [rsp + 0x60]
14004172b:  lea    rcx, [rbp + 0x3d0]
140041732:  movups xmm0, xmmword ptr [rax]
140041735:  movaps xmmword ptr [rsp + 0x60], xmm0
14004173a:  call   0x1402372e0
14004173f:  mov    rdx, rax
140041742:  lea    rcx, [rbp]
140041746:  call   0x140235460
14004174b:  lea    rcx, [rbp]
14004174f:  call   0x140235370
140041754:  mov    r8b, 1
140041757:  lea    rdx, [rbp + 0x3d0]
14004175e:  lea    rcx, [rbp + 0x1df0]
140041765:  call   0x14000efa0
14004176a:  lea    rcx, [rbp + 0xac8]
140041771:  mov    rdx, rax
140041774:  call   0x14000d7a0
140041779:  mov    rcx, rax
14004177c:  call   0x140014830
140041781:  lea    rcx, [rbp + 0x1df0]
140041788:  call   0x140072260
14004178d:  xorps  xmm0, xmm0
140041790:  lea    rdx, [rbp + 0x58]
140041794:  xor    r14d, r14d
140041797:  lea    rcx, [rbp + 0xfc0]
14004179e:  movdqu xmmword ptr [rbp + 0x3b8], xmm0
1400417a6:  mov    qword ptr [rbp + 0x3c8], r14
1400417ad:  call   0x140071c40
1400417b2:  lea    r9, [rbp + 0x78]
1400417b6:  lea    rdx, [rbp + 0xfc0]
1400417bd:  lea    rcx, [rbp + 0x48]
1400417c1:  call   0x140068de0
1400417c6:  lea    rcx, [rbp + 0xfc0]
1400417cd:  call   0x140071e80
1400417d2:  xorps  xmm0, xmm0
1400417d5:  lea    rcx, [rbp + 0x368]
1400417dc:  movdqu xmmword ptr [rbp + 0x368], xmm0
1400417e4:  call   0x140068c90
1400417e9:  mov    rbx, qword ptr [rbp + 0x48]
1400417ed:  movzx  esi, byte ptr [rbp + 0x2da0]
1400417f4:  test   rbx, rbx
1400417f7:  je     0x14004195e
1400417fd:  nop    dword ptr [rax]
140041800:  movzx  eax, byte ptr [r13 + 0x1b0]
140041808:  test   al, al
14004180a:  jne    0x140041b45
140041810:  lea    rdx, [rbx + 0x20]
140041814:  lea    rcx, [rbp + 0x320]
14004181b:  call   0x140071c40
140041820:  test   sil, sil
140041823:  je     0x14004183e
140041825:  lea    rcx, [rbp + 0x320]
14004182c:  call   0x14000e640
140041831:  test   al, al
140041833:  je     0x14004183e
140041835:  mov    byte ptr [rbx + 0x64], r14b
140041839:  jmp    0x140041925
14004183e:  lea    rcx, [rbp + 0x320]
140041845:  call   0x14000e3b0
14004184a:  test   al, al
14004184c:  je     0x140041925
140041852:  lea    r9, [rbp + 0x78]
140041856:  lea    r8, [rbp + 0x58]
14004185a:  lea    rdx, [rbp + 0x320]
140041861:  lea    rcx, [rbp - 0x70]
140041865:  call   0x14000ec40
14004186a:  lea    rcx, [rbp + 0xc70]
140041871:  call   0x140042590
140041876:  lea    rdx, [rbp + 0x1e10]
14004187d:  lea    rcx, [rbp + 0x320]
140041884:  call   0x14000dab0
140041889:  mov    rdx, rax
14004188c:  lea    rcx, [rbp + 0xc90]
140041893:  call   0x140049b10
140041898:  lea    rcx, [rbp + 0x1e10]
14004189f:  call   0x140072260
1400418a4:  lea    rdx, [rbp + 0x1e30]
1400418ab:  lea    rcx, [rbp - 0x70]
1400418af:  call   0x14000dab0
1400418b4:  mov    rdx, rax
1400418b7:  lea    rcx, [rbp + 0xc70]
1400418be:  call   0x140049b10
1400418c3:  lea    rcx, [rbp + 0x1e30]
1400418ca:  call   0x140072260
1400418cf:  lea    rcx, [rbp + 0x320]
1400418d6:  call   0x14000ee30
1400418db:  mov    dl, 0x2f
1400418dd:  mov    dword ptr [rbp + 0xcb0], eax
1400418e3:  lea    rcx, [rbp + 0xc90]
1400418ea:  call   0x14000c3f0
1400418ef:  mov    dl, 0x2f
1400418f1:  lea    rcx, [rbp + 0xc70]
1400418f8:  call   0x14000c3f0
1400418fd:  lea    rdx, [rbp + 0xc70]
140041904:  lea    rcx, [rbp + 0x3b8]
14004190b:  call   0x140046030
140041910:  lea    rcx, [rbp + 0xc70]
140041917:  call   0x1400425c0
14004191c:  lea    rcx, [rbp - 0x70]
140041920:  call   0x140071e80
140041925:  lea    rcx, [rbp + 0x320]
14004192c:  call   0x140071e80
140041931:  lea    rcx, [rbp + 0x48]
140041935:  call   0x140068710
14004193a:  xorps  xmm0, xmm0
14004193d:  lea    rcx, [rbp + 0x368]
140041944:  movdqu xmmword ptr [rbp + 0x368], xmm0
14004194c:  call   0x140068c90
140041951:  mov    rbx, qword ptr [rbp + 0x48]
140041955:  test   rbx, rbx
140041958:  jne    0x140041800
14004195e:  lea    rcx, [rbp + 0x48]
140041962:  call   0x140068c90
140041967:  mov    rdi, qword ptr [rbp + 0x1a0]
14004196e:  mov    rbx, qword ptr [rbp + 0x198]
140041975:  cmp    rbx, rdi
140041978:  je     0x140041b27
14004197e:  nop    
140041980:  lea    rdx, [rbp + 0x58]
140041984:  lea    rcx, [rbp + 0x1000]
14004198b:  call   0x140071c40
140041990:  mov    rdx, rbx
140041993:  lea    rcx, [rbp + 0xfe0]
14004199a:  call   0x140071c40
14004199f:  lea    r9, [rbp + 0x78]
1400419a3:  lea    r8, [rbp + 0x1000]
1400419aa:  lea    rdx, [rbp + 0xfe0]
1400419b1:  lea    rcx, [rbp + 0x1e50]
1400419b8:  call   0x14006c690
1400419bd:  mov    qword ptr [rbp + 0x480], r14
1400419c4:  lea    rcx, [rbp + 0x1e50]
1400419cb:  mov    qword ptr [rbp + 0x488], r14
1400419d2:  xorps  xmm0, xmm0
1400419d5:  movups xmmword ptr [rbp + 0x470], xmm0
1400419dc:  movups xmm0, xmmword ptr [rax]
1400419df:  movups xmmword ptr [rbp + 0x470], xmm0
1400419e6:  movups xmm1, xmmword ptr [rax + 0x10]
1400419ea:  movups xmmword ptr [rbp + 0x480], xmm1
1400419f1:  mov    qword ptr [rax + 0x10], r14
1400419f5:  mov    qword ptr [rax + 0x18], 7
1400419fd:  mov    word ptr [rax], r14w
140041a01:  call   0x140071e80
140041a06:  lea    rcx, [rbp + 0xfe0]
140041a0d:  call   0x140071e80
140041a12:  lea    rcx, [rbp + 0x1000]
140041a19:  call   0x140071e80
140041a1e:  xorps  xmm0, xmm0
140041a21:  mov    qword ptr [rbp + 0x430], r14
140041a28:  movups xmmword ptr [rbp + 0x420], xmm0
140041a2f:  lea    rdx, [rbp + 0x1e70]
140041a36:  mov    rcx, rbx
140041a39:  movups xmmword ptr [rbp + 0x440], xmm0
140041a40:  mov    byte ptr [rbp + 0x420], r14b
140041a47:  mov    byte ptr [rbp + 0x440], r14b
140041a4e:  mov    qword ptr [rbp + 0x438], 0xf
140041a59:  mov    qword ptr [rbp + 0x450], r14
140041a60:  mov    qword ptr [rbp + 0x458], 0xf
140041a6b:  call   0x14000dab0
140041a70:  mov    rdx, rax
140041a73:  lea    rcx, [rbp + 0x440]
140041a7a:  call   0x140049b10
140041a7f:  lea    rcx, [rbp + 0x1e70]
140041a86:  call   0x140072260
140041a8b:  lea    rdx, [rbp + 0x1e90]
140041a92:  lea    rcx, [rbp + 0x470]
140041a99:  call   0x14000dab0
140041a9e:  mov    rdx, rax
140041aa1:  lea    rcx, [rbp + 0x420]
140041aa8:  call   0x140049b10
140041aad:  lea    rcx, [rbp + 0x1e90]
140041ab4:  call   0x140072260
140041ab9:  mov    rcx, rbx
140041abc:  call   0x14000ee30
140041ac1:  mov    dl, 0x2f
140041ac3:  mov    dword ptr [rbp + 0x460], eax
140041ac9:  lea    rcx, [rbp + 0x440]
140041ad0:  call   0x14000c3f0
140041ad5:  mov    dl, 0x2f
140041ad7:  lea    rcx, [rbp + 0x420]
140041ade:  call   0x14000c3f0
140041ae3:  lea    rdx, [rbp + 0x420]
140041aea:  lea    rcx, [rbp + 0x3b8]
140041af1:  call   0x140046030
140041af6:  lea    rcx, [rbp + 0x440]
140041afd:  call   0x140072260
140041b02:  lea    rcx, [rbp + 0x420]
140041b09:  call   0x140072260
140041b0e:  lea    rcx, [rbp + 0x470]
140041b15:  call   0x140071e80
140041b1a:  add    rbx, 0x20
140041b1e:  cmp    rbx, rdi
140041b21:  jne    0x140041980
140041b27:  movzx  eax, byte ptr [r13 + 0x1b0]
140041b2f:  test   al, al
140041b31:  jne    0x140041f3c
140041b37:  or     sil, r12b
140041b3a:  jne    0x140041b53
140041b3c:  mov    edx, dword ptr [r13 + 0xe8]
140041b43:  jmp    0x140041b58
140041b45:  lea    rcx, [rbp + 0x48]
140041b49:  call   0x140068c90
140041b4e:  jmp    0x140041f3c
140041b53:  mov    edx, 0xe
140041b58:  mov    rcx, qword ptr [rip + 0xa73ee1]
140041b5f:  xorps  xmm0, xmm0
140041b62:  mov    qword ptr [rbp + 0x98], 8
140041b6d:  mov    eax, 0xf
140041b72:  mov    qword ptr [rbp + 0xa0], rax
140041b79:  movups xmmword ptr [rbp + 0x88], xmm0
140041b80:  mov    qword ptr [rbp + 0x88], rcx
140041b87:  mov    byte ptr [rbp + 0x90], r14b
140041b8e:  test   edx, edx
140041b90:  jle    0x140041c6c
140041b96:  lea    rcx, [rip + 0xa73eaf]   ; "%04i"
140041b9d:  call   0x1402112e0
140041ba2:  mov    rcx, qword ptr [rbp + 0x98]
140041ba9:  lea    rdx, [rbp + 0x88]
140041bb0:  mov    r8d, 4
140041bb6:  mov    qword ptr [rbp + 0x270], r14
140041bbd:  cmp    rcx, r8
140041bc0:  mov    qword ptr [rbp + 0x278], r14
140041bc7:  xorps  xmm0, xmm0
140041bca:  mov    rbx, rax
140041bcd:  cmovb  r8, rcx
140041bd1:  cmp    qword ptr [rbp + 0xa0], 0xf
140041bd9:  lea    rcx, [rbp + 0x260]
140041be0:  cmova  rdx, qword ptr [rbp + 0x88]
140041be8:  movups xmmword ptr [rbp + 0x260], xmm0
140041bef:  call   0x1400718b0
140041bf4:  mov    r8, rbx
140041bf7:  lea    rdx, [rbp + 0x260]
140041bfe:  lea    rcx, [rbp + 0x1eb0]
140041c05:  call   0x1400425e0
140041c0a:  mov    rdx, rax
140041c0d:  lea    rcx, [rbp + 0x88]
140041c14:  call   0x140049b10
140041c19:  lea    rcx, [rbp + 0x1eb0]
140041c20:  call   0x140072260
140041c25:  mov    r8, qword ptr [rbp + 0x278]
140041c2c:  cmp    r8, 0xf
140041c30:  jbe    0x140041c45
140041c32:  mov    rdx, qword ptr [rbp + 0x260]
140041c39:  lea    rcx, [rbp + 0x260]
140041c40:  call   0x140072220
140041c45:  mov    rax, qword ptr [rbp + 0xa0]
140041c4c:  mov    rcx, qword ptr [rbp + 0x88]
140041c53:  mov    qword ptr [rbp + 0x270], r14
140041c5a:  mov    qword ptr [rbp + 0x278], 0xf
140041c65:  mov    byte ptr [rbp + 0x260], r14b
140041c6c:  cmp    rax, 0xf
140041c70:  lea    rdi, [rbp + 0x88]
140041c77:  lea    rdx, [rbp + 0x1ed0]
140041c7e:  cmova  rdi, rcx
140041c82:  lea    rcx, [rbp + 0x2c0]
140041c89:  call   0x14000dab0
140041c8e:  lea    rdx, [rbp + 0x3b8]
140041c95:  mov    rbx, rax
140041c98:  lea    rcx, [rbp + 0x2250]
140041c9f:  call   0x140046230
140041ca4:  mov    rcx, rax
140041ca7:  mov    r8, rdi
140041caa:  mov    rdx, rbx
140041cad:  call   0x14020a660
140041cb2:  lea    rcx, [rbp + 0x1ed0]
140041cb9:  movzx  ebx, al
140041cbc:  call   0x140072260
140041cc1:  test   bl, bl
140041cc3:  jne    0x140041de1
140041cc9:  xorps  xmm0, xmm0
140041ccc:  mov    qword ptr [rbp + 0x180], r14
140041cd3:  mov    ecx, 0x40
140041cd8:  mov    qword ptr [rbp + 0x188], r14
140041cdf:  movups xmmword ptr [rbp + 0x170], xmm0
140041ce6:  call   0x1400723e0
140041ceb:  movups xmm0, xmmword ptr [rip + 0xa73d66]
140041cf2:  mov    qword ptr [rbp + 0x170], rax
140041cf9:  lea    rdx, [rbp + 0xa50]
140041d00:  movups xmm1, xmmword ptr [rip + 0xa73d61]
140041d07:  mov    rcx, r15
140041d0a:  mov    qword ptr [rbp + 0x180], 0x38
140041d15:  movss  xmm2, dword ptr [rip + 0xaae657]
140041d1d:  mov    qword ptr [rbp + 0x188], 0x3f
140041d28:  movups xmmword ptr [rax], xmm0
140041d2b:  movups xmm0, xmmword ptr [rip + 0xa73d46]
140041d32:  movups xmmword ptr [rax + 0x10], xmm1
140041d36:  movsd  xmm1, qword ptr [rip + 0xa73d4a]
140041d3e:  movups xmmword ptr [rax + 0x20], xmm0
140041d42:  movsd  qword ptr [rax + 0x30], xmm1
140041d47:  mov    byte ptr [rax + 0x38], r14b
140041d4b:  movups xmm1, xmmword ptr [rbp + 0x180]
140041d52:  mov    byte ptr [rbp + 0x190], 1
140041d59:  movups xmm0, xmmword ptr [rbp + 0x170]
140041d60:  mov    qword ptr [rbp + 0x180], r14
140041d67:  movups xmmword ptr [rbp + 0xa60], xmm1
140041d6e:  mov    qword ptr [rbp + 0x188], 0xf
140041d79:  movups xmmword ptr [rbp + 0xa50], xmm0
140041d80:  mov    byte ptr [rbp + 0x170], r14b
140041d87:  movss  dword ptr [rbp + 0x194], xmm2
140041d8f:  movss  dword ptr [rbp + 0xa74], xmm2
140041d97:  mov    byte ptr [rbp + 0xa70], 1
140041d9e:  call   0x1400472f0
140041da3:  mov    r8, qword ptr [rbp + 0x188]
140041daa:  cmp    r8, 0xf
140041dae:  jbe    0x140041dc3
140041db0:  mov    rdx, qword ptr [rbp + 0x170]
140041db7:  lea    rcx, [rbp + 0x170]
140041dbe:  call   0x140072220
140041dc3:  mov    qword ptr [rbp + 0x180], r14
140041dca:  mov    qword ptr [rbp + 0x188], 0xf
140041dd5:  mov    byte ptr [rbp + 0x170], r14b
140041ddc:  jmp    0x140041f03
140041de1:  lea    rcx, [rbp + 0x58]
140041de5:  call   0x14000ea80
140041dea:  xorps  xmm0, xmm0
140041ded:  mov    qword ptr [rbp + 0x108], r14
140041df4:  mov    ecx, 0x40
140041df9:  mov    qword ptr [rbp + 0x110], r14
140041e00:  movups xmmword ptr [rbp + 0xf8], xmm0
140041e07:  call   0x1400723e0
140041e0c:  movups xmm0, xmmword ptr [rip + 0xa7369d]
140041e13:  mov    qword ptr [rbp + 0xf8], rax
140041e1a:  lea    rdx, [rbp + 0xa78]
140041e21:  movups xmm1, xmmword ptr [rip + 0xa73698]
140041e28:  mov    qword ptr [rbp + 0x108], 0x37
140041e33:  mov    rcx, r15
140041e36:  mov    qword ptr [rbp + 0x110], 0x3f
140041e41:  movups xmmword ptr [rax], xmm0
140041e44:  movups xmm0, xmmword ptr [rip + 0xa73685]
140041e4b:  movups xmmword ptr [rax + 0x10], xmm1
140041e4f:  movsd  xmm1, qword ptr [rip + 0xa73688]
140041e57:  movups xmmword ptr [rax + 0x20], xmm0
140041e5b:  movsd  qword ptr [rax + 0x2f], xmm1
140041e60:  mov    byte ptr [rax + 0x37], r14b
140041e64:  movups xmm1, xmmword ptr [rbp + 0x108]
140041e6b:  mov    byte ptr [rbp + 0x118], r14b
140041e72:  movups xmm0, xmmword ptr [rbp + 0xf8]
140041e79:  mov    qword ptr [rbp + 0x108], r14
140041e80:  movups xmmword ptr [rbp + 0xa88], xmm1
140041e87:  mov    qword ptr [rbp + 0x110], 0xf
140041e92:  movups xmmword ptr [rbp + 0xa78], xmm0
140041e99:  mov    byte ptr [rbp + 0xf8], r14b
140041ea0:  movss  dword ptr [rbp + 0x11c], xmm10
140041ea9:  movss  dword ptr [rbp + 0xa9c], xmm10
140041eb2:  mov    byte ptr [rbp + 0xa98], r14b
140041eb9:  call   0x1400472f0
140041ebe:  mov    r8, qword ptr [rbp + 0x110]
140041ec5:  cmp    r8, 0xf
140041ec9:  jbe    0x140041ede
140041ecb:  mov    rdx, qword ptr [rbp + 0xf8]
140041ed2:  lea    rcx, [rbp + 0xf8]
140041ed9:  call   0x140072220
140041ede:  mov    eax, 1
140041ee3:  mov    qword ptr [rbp + 0x108], r14
140041eea:  mov    qword ptr [rbp + 0x110], 0xf
140041ef5:  mov    byte ptr [rbp + 0xf8], r14b
140041efc:  xchg   byte ptr [r13 + 0x1b0], al
140041f03:  mov    r8, qword ptr [rbp + 0xa0]
140041f0a:  cmp    r8, 0xf
140041f0e:  jbe    0x140041f23
140041f10:  mov    rdx, qword ptr [rbp + 0x88]
140041f17:  lea    rcx, [rbp + 0x88]
140041f1e:  call   0x140072220
140041f23:  mov    qword ptr [rbp + 0x98], r14
140041f2a:  mov    qword ptr [rbp + 0xa0], 0xf
140041f35:  mov    byte ptr [rbp + 0x88], r14b
140041f3c:  lea    rcx, [rbp + 0x3b8]
140041f43:  call   0x14004f480
140041f48:  lea    rcx, [rbp + 0x3d0]
140041f4f:  call   0x140236cc0
140041f54:  lea    rcx, [rbp + 0x3e0]
140041f5b:  mov    qword ptr [rbp + 0x3d0], r14
140041f62:  call   0x1402350d0
140041f67:  lea    rcx, [rbp + 0x1020]
140041f6e:  call   0x140072260
140041f73:  lea    rcx, [rbp + 0xac8]
140041f7a:  call   0x140071e80
140041f7f:  mov    rbx, qword ptr [rbp + 0x198]
140041f86:  test   rbx, rbx
140041f89:  je     0x140040641
140041f8f:  mov    rdi, qword ptr [rbp + 0x1a0]
140041f96:  cmp    rbx, rdi
140041f99:  je     0x140041fb8
140041f9b:  nop    dword ptr [rax + rax]
140041fa0:  mov    rcx, rbx
140041fa3:  call   0x140071e80
140041fa8:  add    rbx, 0x20
140041fac:  cmp    rbx, rdi
140041faf:  jne    0x140041fa0
140041fb1:  mov    rbx, qword ptr [rbp + 0x198]
140041fb8:  mov    rdx, qword ptr [rbp + 0x1a8]
140041fbf:  sub    rdx, rbx
140041fc2:  and    rdx, 0xffffffffffffffe0
140041fc6:  cmp    rdx, 0x1000
140041fcd:  jb     0x140041fea
140041fcf:  mov    rcx, qword ptr [rbx - 8]
140041fd3:  sub    rbx, rcx
140041fd6:  sub    rbx, 8
140041fda:  cmp    rbx, 0x1f
140041fde:  ja     0x14004215d
140041fe4:  add    rdx, 0x27
140041fe8:  jmp    0x140041fed
140041fea:  mov    rcx, rbx
140041fed:  call   0x14052e6f0
140041ff2:  xorps  xmm0, xmm0
140041ff5:  mov    qword ptr [rbp + 0x1a8], r14
140041ffc:  movdqu xmmword ptr [rbp + 0x198], xmm0
140042004:  jmp    0x140040641
140042009:  mov    rax, rcx
14004200c:  mov    rcx, rax
14004200f:  call   0x14052e6f0
140042014:  xorps  xmm0, xmm0
140042017:  mov    qword ptr [rbp - 0x38], 0
14004201f:  movdqu xmmword ptr [rbp - 0x48], xmm0
140042024:  mov    rdx, qword ptr [rbp + 0x2b8]
14004202b:  cmp    rdx, 0xf
14004202f:  jbe    0x140042065
140042031:  mov    rcx, qword ptr [rbp + 0x2a0]
140042038:  inc    rdx
14004203b:  cmp    rdx, 0x1000
140042042:  jb     0x140042060
140042044:  mov    rax, qword ptr [rcx - 8]
140042048:  sub    rcx, rax
14004204b:  sub    rcx, 8
14004204f:  cmp    rcx, 0x1f
140042053:  ja     0x14004215d
140042059:  add    rdx, 0x27
14004205d:  mov    rcx, rax
140042060:  call   0x14052e6f0
140042065:  mov    rbx, qword ptr [rbp - 0x18]
140042069:  xor    esi, esi
14004206b:  mov    qword ptr [rbp + 0x2b0], rsi
140042072:  mov    qword ptr [rbp + 0x2b8], 0xf
14004207d:  mov    byte ptr [rbp + 0x2a0], sil
140042084:  test   rbx, rbx
140042087:  je     0x1400420ea
140042089:  mov    rdi, qword ptr [rbp - 0x10]
14004208d:  cmp    rbx, rdi
140042090:  je     0x1400420a7
140042092:  mov    rcx, rbx
140042095:  call   0x140072260
14004209a:  add    rbx, 0x20
14004209e:  cmp    rbx, rdi
1400420a1:  jne    0x140042092
1400420a3:  mov    rbx, qword ptr [rbp - 0x18]
1400420a7:  mov    rdx, qword ptr [rbp - 8]
1400420ab:  sub    rdx, rbx
1400420ae:  and    rdx, 0xffffffffffffffe0
1400420b2:  cmp    rdx, 0x1000
1400420b9:  jb     0x1400420d6
1400420bb:  mov    rcx, qword ptr [rbx - 8]
1400420bf:  sub    rbx, rcx
1400420c2:  sub    rbx, 8
1400420c6:  cmp    rbx, 0x1f
1400420ca:  ja     0x14004215d
1400420d0:  add    rdx, 0x27
1400420d4:  jmp    0x1400420d9
1400420d6:  mov    rcx, rbx
1400420d9:  call   0x14052e6f0
1400420de:  xorps  xmm0, xmm0
1400420e1:  mov    qword ptr [rbp - 8], rsi
1400420e5:  movdqu xmmword ptr [rbp - 0x18], xmm0
1400420ea:  lea    rcx, [rbp + 0x2e0]
1400420f1:  call   0x140071e80
1400420f6:  lea    rcx, [rbp + 0x280]
1400420fd:  call   0x140071e80
140042102:  mov    rdx, qword ptr [rbp + 0x1c8]
140042109:  cmp    rdx, 0xf
14004210d:  jbe    0x14004213f
14004210f:  mov    rcx, qword ptr [rbp + 0x1b0]
140042116:  inc    rdx
140042119:  cmp    rdx, 0x1000
140042120:  jb     0x14004213a
140042122:  mov    rax, qword ptr [rcx - 8]
140042126:  sub    rcx, rax
140042129:  sub    rcx, 8
14004212d:  cmp    rcx, 0x1f
140042131:  ja     0x14004215d
140042133:  add    rdx, 0x27
140042137:  mov    rcx, rax
14004213a:  call   0x14052e6f0
14004213f:  mov    qword ptr [rbp + 0x1c0], rsi
140042146:  mov    qword ptr [rbp + 0x1c8], 0xf
140042151:  mov    byte ptr [rbp + 0x1b0], 0
140042158:  jmp    0x140042394
14004215d:  mov    ecx, 5
140042162:  int    0x29
140042164:  xorps  xmm0, xmm0
140042167:  mov    qword ptr [rbp + 0x130], rsi
14004216e:  mov    ecx, 0x50
140042173:  mov    qword ptr [rbp + 0x138], rsi
14004217a:  movups xmmword ptr [rbp + 0x120], xmm0
140042181:  call   0x1400723e0
140042186:  movaps xmm0, xmmword ptr [rip + 0xa734a3]
14004218d:  lea    rdx, [rbp + 0xaa0]
140042194:  movaps xmm1, xmmword ptr [rip + 0xa734a5]
14004219b:  mov    rcx, rax
14004219e:  movss  xmm2, dword ptr [rip + 0xaae1ce]
1400421a6:  mov    qword ptr [rbp + 0x120], rax
1400421ad:  mov    qword ptr [rbp + 0x130], 0x42
1400421b8:  mov    qword ptr [rbp + 0x138], 0x4f
1400421c3:  movups xmmword ptr [rax], xmm0
1400421c6:  movaps xmm0, xmmword ptr [rip + 0xa73483]
1400421cd:  movups xmmword ptr [rax + 0x10], xmm1
1400421d1:  movaps xmm1, xmmword ptr [rip + 0xa73488]
1400421d8:  movups xmmword ptr [rax + 0x20], xmm0
1400421dc:  movups xmmword ptr [rax + 0x30], xmm1
1400421e0:  movzx  eax, word ptr [rip + 0xa73489]
1400421e7:  mov    word ptr [rcx + 0x40], ax
1400421eb:  mov    byte ptr [rcx + 0x42], sil
1400421ef:  mov    rcx, r15
1400421f2:  movups xmm1, xmmword ptr [rbp + 0x130]
1400421f9:  mov    byte ptr [rbp + 0x140], r12b
140042200:  movups xmm0, xmmword ptr [rbp + 0x120]
140042207:  mov    qword ptr [rbp + 0x130], rsi
14004220e:  movups xmmword ptr [rbp + 0xab0], xmm1
140042215:  mov    qword ptr [rbp + 0x138], 0xf
140042220:  movups xmmword ptr [rbp + 0xaa0], xmm0
140042227:  mov    byte ptr [rbp + 0x120], sil
14004222e:  movss  dword ptr [rbp + 0x144], xmm2
140042236:  movss  dword ptr [rbp + 0xac4], xmm2
14004223e:  mov    byte ptr [rbp + 0xac0], r12b
140042245:  call   0x1400472f0
14004224a:  mov    r8, qword ptr [rbp + 0x138]
140042251:  cmp    r8, 0xf
140042255:  jbe    0x14004226a
140042257:  mov    rdx, qword ptr [rbp + 0x120]
14004225e:  lea    rcx, [rbp + 0x120]
140042265:  call   0x140072220
14004226a:  mov    qword ptr [rbp + 0x130], rsi
140042271:  mov    qword ptr [rbp + 0x138], 0xf
14004227c:  mov    byte ptr [rbp + 0x120], sil
140042283:  jmp    0x140042394
140042288:  xorps  xmm0, xmm0
14004228b:  mov    qword ptr [rbp + 0x228], rsi
140042292:  mov    ecx, 0x50
140042297:  mov    qword ptr [rbp + 0x230], rsi
14004229e:  movups xmmword ptr [rbp + 0x218], xmm0
1400422a5:  call   0x1400723e0
1400422aa:  movaps xmm0, xmmword ptr [rip + 0xa732ef]
1400422b1:  lea    rdx, [rbp + 0x778]
1400422b8:  movaps xmm1, xmmword ptr [rip + 0xa732f1]
1400422bf:  mov    rcx, rax
1400422c2:  mov    qword ptr [rbp + 0x218], rax
1400422c9:  mov    qword ptr [rbp + 0x228], 0x43
1400422d4:  mov    qword ptr [rbp + 0x230], 0x4f
1400422df:  movups xmmword ptr [rax], xmm0
1400422e2:  movaps xmm0, xmmword ptr [rip + 0xa732d7]
1400422e9:  movups xmmword ptr [rax + 0x10], xmm1
1400422ed:  movaps xmm1, xmmword ptr [rip + 0xa732dc]
1400422f4:  movups xmmword ptr [rax + 0x20], xmm0
1400422f8:  movss  xmm0, dword ptr [rip + 0xaae074]
140042300:  movups xmmword ptr [rax + 0x30], xmm1
140042304:  mov    eax, dword ptr [rip + 0xa732d5]
14004230a:  mov    dword ptr [rcx + 0x3f], eax
14004230d:  mov    byte ptr [rcx + 0x43], sil
140042311:  mov    rcx, r15
140042314:  mov    rax, qword ptr [rbp + 0x218]
14004231b:  mov    qword ptr [rbp + 0x778], rax
140042322:  mov    rax, qword ptr [rbp + 0x220]
140042329:  mov    qword ptr [rbp + 0x780], rax
140042330:  mov    rax, qword ptr [rbp + 0x228]
140042337:  mov    qword ptr [rbp + 0x788], rax
14004233e:  mov    rax, qword ptr [rbp + 0x230]
140042345:  mov    qword ptr [rbp + 0x790], rax
14004234c:  movss  dword ptr [rbp + 0x23c], xmm0
140042354:  movss  dword ptr [rbp + 0x79c], xmm0
14004235c:  mov    byte ptr [rbp + 0x238], 1
140042363:  mov    qword ptr [rbp + 0x228], rsi
14004236a:  mov    qword ptr [rbp + 0x230], 0xf
140042375:  mov    byte ptr [rbp + 0x218], sil
14004237c:  mov    byte ptr [rbp + 0x798], 1
140042383:  call   0x1400472f0
140042388:  lea    rcx, [rbp + 0x218]
14004238f:  call   0x140072260
140042394:  lea    rcx, [rbp + 0x2c0]
14004239b:  call   0x140071e80
1400423a0:  lea    rcx, [rbp + 0x28]
1400423a4:  call   0x140071e80
1400423a9:  lea    rcx, [rbp + 0x990]
1400423b0:  call   0x140071e80
1400423b5:  lea    rcx, [rbp + 0x58]
1400423b9:  call   0x140071e80
1400423be:  movaps xmm12, xmmword ptr [rsp + 0x2dd0]
1400423c7:  movaps xmm11, xmmword ptr [rsp + 0x2de0]
1400423d0:  movaps xmm10, xmmword ptr [rsp + 0x2df0]
1400423d9:  movaps xmm9, xmmword ptr [rsp + 0x2e00]
1400423e2:  movaps xmm8, xmmword ptr [rsp + 0x2e10]
1400423eb:  movaps xmm7, xmmword ptr [rsp + 0x2e20]
1400423f3:  movaps xmm6, xmmword ptr [rsp + 0x2e30]
1400423fb:  mov    r15, qword ptr [rsp + 0x2e48]
140042403:  mov    r14, qword ptr [rsp + 0x2e50]
14004240b:  mov    r12, qword ptr [rsp + 0x2e58]
140042413:  mov    rdi, qword ptr [rsp + 0x2e60]
14004241b:  add    rsp, 0x2e68
140042422:  pop    r13
140042424:  pop    rsi
140042425:  pop    rbx
140042426:  pop    rbp
140042427:  ret    
140042428:  call   0x140056ef0
14004242d:  int3   
14004242e:  call   0x1400723c0
140042433:  int3   
