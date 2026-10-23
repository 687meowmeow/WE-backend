// scripts.js excerpts (Wallpaper Engine UI, Angular). Minified source was beautified with js-beautify; logic unchanged.

// ===== Gating: which wallpapers can be sent to a phone, plus send/cancel entry points =====
W.canUploadWallpaperToMobileDevice = function(e, t) {
    var a = !1;
    return angular.isArray(e) || (e = [e]), angular.forEach(e, function(e) {
        a = a || "downloadable" !== e.status && ("Scene" === e.type || "Video" === e.type || t) && (!e.localAndExternal || e.file.toLowerCase().endsWith(".mp4") || e.file.toLowerCase().endsWith(".webm"))
    }), a
}, W.filterWallpapersForMobileDevice = function(e) {
    return (e = angular.isArray(e) ? e : [e]).filter(W.canUploadWallpaperToMobileDevice)
}, W.sendToMobileDevice = function(e, a) {
    Oe(e = angular.isArray(e) ? e : [e]).then(De).then(function(e) {
        var t;
        e = W.filterWallpapersForMobileDevice(angular.copy(e)), W.canOpenWallpaperDetailsOnWeb && angular.forEach(e, function(e) {
            var t = W.getWorkshopUrl(e);
            t ? e.sharelink = t : delete e.sharelink
        }), 0 < e.length && (t = W.browserSettings.paireddevices.find(ve(a.id)), a = t || a, W.browserSettings.advertisesendtomobile = !1, F.open("browseMobileUpload", {
            data: {
                device: a,
                location: W.selectedMonitor.location,
                wallpapers: e
            }
        }))
    })
}, W.cancelMobileUploads = function() {
    F.open("genericConfirm", {
        data: {
            title: "ui_browse_mobile_upload_cancel_title",
            message: "ui_browse_mobile_upload_cancel_body",
            okVisible: !0,
            cancelVisible: !0
        }
    }).then(function() {
        return j.callDeferred("browseWallpaperObject", "cancelMobileUploads")
    })
}, W.mobileUploadState = {
    visible: !1,
    progress: 0,
    title: "<title>",
    device: "<device>",
    status: "<status>",
    count: ""
};
var Kt = !(W.onMobileDeviceUploadProgress = function(t) {
            m.cancel(qt), W.mobileUploadState.visible && !t.visible && (qt = m(function() {
                            W.mobileUploadState.visible = !1
                        },

// ===== mobile-manager directive (pairing UI -> native bridge calls) =====
directive("mobileManager", ["$timeout", "$translate", "modals", "utils", "host", function(o, e, r, s, l) {
        return {
            templateUrl: "views/templates/mobilemanager.html",
            restrict: "E",
            scope: {
                mmDevices: "=",
                mmState: "="
            },
            link: function(a) {
                function i(t) {
                    return function(e) {
                        return e.id === t
                    }
                }
                var t;

                function n() {
                    o.cancel(t), l.callDeferred("browseWallpaperObject", "checkFirewall").then(function(e) {
                        a.showFirewallWarning = !e, o.cancel(t), e ? a.isApplyingFixes = !1 : t = o(n, 2e3)
                    })
                }
                a.mmState.page = "", a.qualityPresets = [{
                    label: "ui_browse_mobile_upload_modal_preset_low",
                    value: "low"
                }, {
                    label: "ui_browse_mobile_upload_modal_preset_medium",
                    value: "medium"
                }, {
                    label: "ui_browse_mobile_upload_modal_preset_high",
                    value: "high"
                }], a.mmState.disableFirewallCheck = function() {
                    o.cancel(t)
                }, a.$on("$destroy", function() {
                    o.cancel(t)
                }), a.fixFirewall = function() {
                    o.cancel(t), a.isApplyingFixes = !0, l.callDeferred("browseWallpaperObject", "fixFirewall").then(function() {
                        o.cancel(t), t = o(n, 150)
                    })
                }, a.beginDeviceDiscovery = function() {
                    o.cancel(t), t = o(n, 500), a.mmState.page = "connect", l.callDeferred("browseWallpaperObject", "discoverMobileDevices", !0).then(function(e) {
                        a.currentPin = e.pin.split("").join(" ")
                    })
                }, a.cancelDeviceDiscovery = function() {
                    o.cancel(t), a.mmState.page = "", l.callDeferred("browseWallpaperObject", "discoverMobileDevices")
                }, a.isDeviceConnected = function(e) {
                    return a.mmState.connectedDevices.some(i(e))
                }, a.openDownloadFaq = function() {
                    s.openUrl("var_url_mobile_download")
                }, a.removeDevice = function(t) {
                    e(["ui_browse_mobile_modal_delete_device_title", "ui_browse_mobile_modal_delete_device_message"], {
                        device: t.name
                    }).then(function(e) {
                        return r.open("genericConfirm", {
                            data: {
                                title: e.ui_browse_mobile_modal_delete_device_title,
                                message: e.ui_browse_mobile_modal_delete_device_message,
                                okVisible: !0,
                                cancelVisible: !0
                            }
                        }).then(function() {
                            l.callDeferred("browseWallpaperObject", "removeMobileDevice", t.id), a.mmDevices.findAndRemove(i(t.id)), a.mmState.connectedDevices.findAndRemove(i(t.id))
                        })
                    })
                }
            }
        }
    }]), angular.module("wallpaperbrowserApp").directive("drawCanvas", ["$document", function(J) {
                return {
                    templateUrl: "views/templates/drawcanvas.html",
                    restrict: "E",
                    scope: {
                        dcConfig: "=",
                        dcMaskAttr: "=",
                        dcPaintCallback: "&"
                    },
                    link: function(m, e) {
                            var s, v, a, i, l, c, d, p, u, g, h, n, o, t = document.createElement("canvas"),
                                r = (angular.element(t).addClass("painterOuterCanvas"), e = angular.element(e), angular.element(e.parent(".painterPan")[0])),
                                f = r.parent(".painter")[0],
                                b = angular.element(f),
                                _ = e.find("#draw")[0],
                                y = angular.element(_),
                                B = e.find("#brush")[0],
                                V = angular.element(B),
                                w = (f.appendChild(t), f.appendChild(B), {
                                    foreground: "#fff",
                                    background: "#000",
                                    erase: "rgba(0, 0, 0, 1)",
                                    clear: "rgba(0, 0, 0, 0)",
                                    polyFill: "rgba(255,255,255,0.51)",
                                    polyFillMask: "rgba(255,255,255,1)",
                                    pointSelection: "rgb(255, 0, 0)",
                                    pointHover: "rgb(255, 255, 0)"
                                }),
                                k = !1,
                                x = !1,
                                S = !1,
                                C = !1,
                                E = 1,
                                P = 1,
                                M = 1,
                                A = [],
                                T = {
                                    zoom: 1,
                                    left: 0,
                                    top: 0
                                },
                                R = {
                                    left: 0,
                                    top: 0
                                };

                            function F(e) {
                                return 1 < e.length && e.first() === e.last()
                            }

                            function j(e) {
                                return e * e
                            }

                            function L(e, t) {
                                return j(e.x - t.x) + j(e.y - t.y)
                            }

                            function z(e, t, a) {
                                var i, a = {
                                        x: a.x - t.x,
                                        y: a.y - t.y
                                    },
                                    n = e.x - t.x,
                                    e = e.y - t.y,
                                    o = (o = a, i = Math.sqrt(o.x * o.x + o.y * o.y), o.x /= i, o.y /= i, a.x * n + a.y * e);
                                return a.x *= o, a.y *= o, a.x += t.x, a.y += t.y, a
                            }

                            function H() {
                                var e, t;
                                i && a && (e = a.w / a.h, t = i.w / i.h, E = t < e ? (R.left = 0, R.top = Math.round(.5 * (i.h - i.w / e)), y.css({
                                    height: Math.round(i.w / e) + "px",
                                    width: "100%",
                                    "margin-top": R.top,
                                    "margin-left": 0
                                }), P = i.w / 400 * (a.w / i.w), P = Math.max(1, P), i.w / a.w * P) : (R.left = Math.round(.5 * (i.w - i.h * e)), R.top = 0, y.css({
                                    width: Math.round(i.h * e) + "px",
                                    height: "100%",
                                    "margin-left": R.left,
                                    "margin-top": R.top
                                }), P = i.h / 400 * (a.h / i.h), P = Math.max(1, P), i.h / a.h * P))
                            }

                            function $(i) {
                                var n, o = 16 * (P / T.zoom);
                                return A.forEach(e => {
                                    e.forEach(e => {
                                        var t = e.x - i.offsetX,
                                            a = e.y - i.offsetY,
                                            t = t * t + a * a;
                                        t < o && (n = e, o = t)
                                    })
                                }), n
                            }

                            function U(e) {
                                var r, s = 10 * (P / T.zoom),
                                    l = {
                                        x: e.offsetX,
                                        y: e.offsetY
                                    };
                                return A.forEach(e => {
                                    if (!(e.length < 2))
                                        for (var t, a, i, n = 1; n < e.length; ++n) {
                                            o = l, i = void 0;
                                            var o = 0 === (i = L(t = e[n - 1], a = e[n])) ? L(o, t) : (i = ((o.x - t.x) * (a.x - t.x) + (o.y - t.y) * (a.y - t.y)) / i, i = Math.max(0, Math.min(1, i)), L(o, {
                                                x: t.x + i * (a.x - t.x),
                                                y: t.y + i * (a.y - t.y)
                                            }));
                                            o < s && (s = o, r = e[n])
                                        }
                                }), r
                            }

                            function G(e) {
                                e.x = Math.min(a.w, Math.max(0, Math.round(e.x))), e.y = Math.min(a.h, Math.max(0, Math.round(e.y)))
                            }

                            function q() {
                                H(), s.resetTransform(), s.fillStyle = w.erase, s.globalCompositeOperation = "destination-out", s.fillRect(0, 0, a.w, a.h), s.globalCompositeOperation = "source-over"
                            }

                            function I() {
                                q(), A = [], h = g = g = u = d = p = void 0
                            }

                            function O(e) {
                                e.offsetX = e.offsetX * (_.width / y.innerWidth()), e.offsetY = e.offsetY * (_.height / y.innerHeight())
                            }

                            function N() {
                                var e = (n = n || document.createElement("canvas")).getContext("2d");
                                return n.width = _.width, n.height = _.height, e.clearRect(0, 0, n.width, n.height), e.drawImage(_, 0, 0), e.getImageData(0, 0, a.w, a.h), n.toDataURL("image/png")
                            }

                            function Q(p, u) {
                                p.resetTransform(), p.fillStyle = w.erase, p.globalCompositeOperation = "destination-out", p.fillRect(0, 0, a.w, a.h), p.globalCompositeOperation = "source-over", p.lineWidth = 1, u && (v.resetTransform(), v.fillStyle = w.erase, v.globalCompositeOperation = "destination-out", v.fillRect(0, 0, i.w, i.h), v.globalCompositeOperation = "source-over", v.translate((T.left + R.left) * T.zoom - .5 * i.w * T.zoom + .5 * i.w, (T.top + R.top) * T.zoom - .5 * i.h * T.zo

// ===== Context-menu gating (Send to / Export pkg / 'type not supported') =====
ocation
})
})
}), A.subMenu("ui_browse_context_menu_play_in_window", M)), H(!0, o)), I = (W.isMobileTranscoderAvailable() && o && ("installed" === o.status || "folder" === o.type) && W.canUploadWallpaperToMobileDevice(R, !0) && (A.requestDivider(), R = W.filterWallpapersForMobileDevice(R), C = B.builder(), R.empty() ? C.button("ui_browse_context_menu_mobile_type_not_supported", "fas fa-times", void 0, void 0, "ctxBtnDisabled") : (angular.forEach(W.mobileManagerState.connectedDevices, function(e) {
            C.button(O.instant("ui_browse_context_menu_send_to", {
                name: e.name
            }), "far fa-mobile-android", function() {
                W.sendToMobileDevice(R, e)
            })
        }), C.empty() || C.divider(), C.button("ui_browse_context_menu_export_pkg", "fas fa-file-download", function() {
            Oe(R).then(De).then(function(e) {
                0 < e.length && F.open("browseMobileUpload", {
                    data: {
                        device: {
                            name: "export"
                        },
                        location: W.selectedMonitor && W.selectedMonitor.location,
                        isExporting: !0,
                        wallpapers: e
                    }
                })
            })
        }), C.button("ui_browse_context_menu_connect_new_device", "fas fa-wifi", function() {
            W.mobileDeviceManagementActive = !0
        })), A.subMenu("ui_browse_context_menu_send_to_device", C)), A.requestDivider(), W.sourceIsInstalled && (A.button("ui_browse_context_menu_create_folder", "fas fa-folder-plus", function() {
                    var a = {
                        title: "ui_browse_modal_folder_create_title",
                        cancelVisible: !0,
                        okVisible: !0,
                        textinputVisible: !0
                    };
                    O("ui_browse_context_menu_new_folder_name").then(function(e) {
                            return a.textinput = a.textinputDefault = e, F.open("genericConfirm", {
                                data: a
                            })
                        }).then(function() {
                                var e = W.currentFolder && W.currentFolder.subfolders || W.folders,
                                    t = {
                                        type: "folder",
                                        title: (0 === a.textinput.length ? a.textinputDefault : a.textinput).toString(
