/*
 * wallpaperui.exe  --  FUN_140131830  (Ghidra decompile, lines 57369-59165 of wallpaperui_exe.c)
 *
 * This is a command-line sub-mode dispatcher. param_1 is the first CLI argument; the
 * mobile packaging modes are:
 *   convertProjectToMobile -i <dir>                  transcode every .tex under <dir> via resourcecompiler64.exe
 *   prepareSharedMobileAssets -i <dir> -o <dir> [-clearUnused]
 *                                                    build shared mobile asset tree (effects/presets/particles/scenes/zcompat,
 *                                                    shaders from blobsSM40/HLSL/lut), .tex -> ETC2, copy .vert/.frag/.geom/.h
 *   gifenc -f <frames> -o <out.gif> -w <w> -h <h> [-d <delay>] [-c <colors>] [-dithered true]
 *   (last branch)  -i <pkg> -o <dir>                 open a .pkg and extract all files
 *
 * Only change from the raw dump: every  &DAT_140xxxxxx  label was resolved to the real string
 * from the PE file and annotated  "string" /*DAT_140xxxxxx*/ .  Tables:
 *   PTR_DAT_140abab70[9]  = .obj .fbx .blend .dae .3ds .x .lxo .gltf .json
 *   PTR_DAT_140ababc0[12] = .png .tga .jpeg .jpg .jfif .bmp .psd .ico .gif .dds .tif .tiff
 */
ulonglong FUN_140131830(undefined8 *param_1)

{
  byte *pbVar1;
  code *pcVar2;
  undefined4 *puVar3;
  short *******pppppppsVar4;
  uint uVar5;
  char cVar6;
  char cVar7;
  int iVar8;
  uint uVar9;
  int iVar10;
  longlong lVar11;
  undefined8 *puVar12;
  undefined8 uVar13;
  longlong *plVar14;
  undefined8 *puVar15;
  char *******pppppppcVar16;
  short *******pppppppsVar17;
  undefined4 *puVar18;
  undefined8 uVar19;
  undefined8 *puVar20;
  ulonglong uVar21;
  undefined8 *******pppppppuVar22;
  undefined8 *******pppppppuVar23;
  undefined8 ******ppppppuVar24;
  undefined8 *******pppppppuVar25;
  uint uVar26;
  undefined1 *puVar27;
  ulonglong uVar28;
  longlong lVar29;
  int iVar30;
  int iVar31;
  ulonglong uVar32;
  uint uVar33;
  ulonglong uVar34;
  bool bVar35;
  undefined4 uVar36;
  undefined4 extraout_XMM0_Da;
  undefined4 extraout_XMM0_Da_00;
  undefined4 extraout_XMM0_Da_01;
  undefined4 extraout_XMM0_Da_02;
  undefined4 extraout_XMM0_Da_03;
  undefined4 extraout_XMM0_Da_04;
  undefined4 extraout_XMM0_Da_05;
  undefined4 extraout_XMM0_Da_06;
  undefined4 extraout_XMM0_Da_07;
  undefined4 extraout_XMM0_Da_08;
  undefined4 local_res10;
  ulonglong local_res18;
  ulonglong local_res20;
  undefined1 auStack_a68 [8];
  undefined1 auStack_a60 [24];
  undefined8 *******local_a48;
  int local_a40;
  undefined1 local_a38;
  undefined8 *******local_a28;
  undefined8 *******pppppppuStack_a20;
  longlong local_a18;
  ulonglong local_a10;
  undefined8 *******local_a08;
  longlong lStack_a00;
  longlong local_9f8;
  ulonglong uStack_9f0;
  undefined8 local_9e8;
  undefined4 uStack_9e0;
  undefined4 uStack_9dc;
  undefined4 local_9d8;
  undefined4 uStack_9d4;
  undefined4 uStack_9d0;
  undefined4 uStack_9cc;
  undefined8 *******local_9c8;
  undefined8 uStack_9c0;
  undefined8 local_9b8;
  ulonglong local_9b0;
  undefined8 *******local_9a8;
  undefined8 *******pppppppuStack_9a0;
  longlong local_998;
  undefined4 local_988;
  undefined4 uStack_984;
  undefined **local_980;
  undefined8 local_978;
  undefined8 uStack_970;
  undefined4 uStack_968;
  undefined4 uStack_964;
  undefined8 uStack_960;
  longlong local_958;
  longlong lStack_950;
  undefined8 local_948;
  undefined8 *****local_938;
  longlong lStack_930;
  undefined8 local_928;
  undefined8 *******local_918;
  longlong local_910;
  undefined8 ******local_8f8 [4];
  undefined8 *******local_8d8;
  undefined4 *local_8d0;
  undefined8 uStack_8c8;
  undefined2 local_8c0;
  undefined2 uStack_8be;
  undefined2 uStack_8bc;
  undefined2 uStack_8ba;
  undefined6 uStack_8b8;
  undefined2 uStack_8b2;
  undefined6 uStack_8b0;
  undefined2 local_8aa;
  undefined8 *******local_8a0;
  undefined1 local_898 [32];
  ulonglong local_878 [2];
  int local_868;
  undefined8 *******local_860 [3];
  ulonglong local_848;
  undefined8 local_838;
  longlong lStack_830;
  undefined8 local_828;
  undefined8 local_820;
  undefined8 local_818;
  undefined8 uStack_810;
  undefined8 local_808;
  undefined8 local_800;
  undefined4 local_7f8;
  longlong local_7e8 [4];
  char *******local_7c8 [2];
  ulonglong local_7b8;
  ulonglong local_7b0;
  char *******local_7a8 [2];
  ulonglong local_798;
  ulonglong local_790;
  short *******local_788 [2];
  longlong local_778;
  ulonglong local_770;
  undefined8 ******local_768 [2];
  undefined1 local_758 [16];
  undefined1 local_748 [9];
  char local_73f;
  int local_73c;
  undefined1 local_728 [32];
  undefined8 ******local_708 [4];
  undefined1 local_6e8 [32];
  undefined1 local_6c8 [240];
  undefined1 local_5d8 [104];
  undefined1 local_570;
  undefined1 local_308 [720];
  
  puVar27 = auStack_a68;
  uVar28 = 0;
  local_res10 = 0;
  pppppppuStack_a20 = (undefined8 *******)0x0;
  local_878[0] = 0;
  uVar32 = 0xcbf29ce484222325;
  local_a28 = (undefined8 *******)0x3436782d;
  local_a18 = 4;
  local_a10 = 0xf;
  uVar21 = uVar28;
  do {
    pbVar1 = (byte *)((longlong)&local_a28 + uVar21);
    uVar21 = uVar21 + 1;
    uVar32 = (uVar32 ^ *pbVar1) * 0x100000001b3;
  } while (uVar21 < 4);
  lVar11 = FUN_140052fa0(&DAT_140bdf6d0,&local_9a8,&local_a28,uVar32);
  lVar29 = DAT_140bdf6d8;
  if (*(longlong *)(lVar11 + 8) != 0) {
    lVar29 = *(longlong *)(lVar11 + 8);
  }
  bVar35 = lVar29 != DAT_140bdf6d8;
  FUN_140072260(&local_a28);
  local_a18 = 5;
  pppppppuStack_a20 = (undefined8 *******)0x0;
  local_a10 = 0xf;
  local_a28 = (undefined8 *******)0x687461702d;
  FUN_140137060(0,local_860,&local_a28);
  uVar21 = param_1[3];
  lVar29 = param_1[2];
  puVar12 = param_1;
  if (0xf < uVar21) {
    puVar12 = (undefined8 *)*param_1;
  }
  if ((lVar29 == 0xe) && (iVar8 = FUN_1409d1dc0(puVar12,"installService",0xe), iVar8 == 0)) {
    pppppppuVar25 = local_860;
    if (0xf < local_848) {
      pppppppuVar25 = local_860[0];
    }
    cVar6 = FUN_1404f4170(1,bVar35,pppppppuVar25);
    uVar34 = (ulonglong)(-(uint)(cVar6 != '\0') & 0x2a);
  }
  else {
    puVar12 = param_1;
    if (0xf < uVar21) {
      puVar12 = (undefined8 *)*param_1;
    }
    if ((lVar29 == 0x10) && (iVar8 = FUN_1409d1dc0(puVar12,"uninstallService",0x10), iVar8 == 0)) {
LAB_140131b65:
      FUN_1404f4170(0,bVar35,0);
      uVar34 = 0x2a;
    }
    else {
      uVar32 = 0;
      puVar12 = param_1;
      if (0xf < uVar21) {
        puVar12 = (undefined8 *)*param_1;
      }
      uVar34 = uVar32;
      if ((lVar29 == 0x12) && (iVar8 = FUN_1409d1dc0(puVar12,"componentReinstall",0x12), iVar8 == 0)
         ) {
        local_a18 = 0xc;
        cVar7 = '\0';
        local_a28 = (undefined8 *******)0x707574726174732d;
        local_a10 = 0xf;
        pppppppuStack_a20 = (undefined8 *******)0x6b736154;
        cVar6 = FUN_140112aa0(&DAT_140bdf6d0,&local_a28);
        if (cVar6 != '\0') {
          pppppppuVar25 = local_860;
          if (0xf < local_848) {
            pppppppuVar25 = local_860[0];
          }
          cVar7 = FUN_1404f3550(1,bVar35,pppppppuVar25);
        }
        local_9f8 = 8;
        local_a08 = (undefined8 *******)0x656369767265732d;
        uStack_9f0 = 0xf;
        lStack_a00 = 0;
        cVar6 = FUN_140112aa0(&DAT_140bdf6d0,&local_a08);
        if (cVar6 != '\0') {
          pppppppuVar25 = local_860;
          if (0xf < local_848) {
            pppppppuVar25 = local_860[0];
          }
          cVar7 = FUN_1404f4170(1,bVar35,pppppppuVar25);
        }
        local_a18 = 0xc;
        local_a10 = 0xf;
        local_a28 = (undefined8 *******)0x736e65657263732d;
        pppppppuStack_a20 = (undefined8 *******)0x72657661;
        cVar6 = FUN_140112aa0(&DAT_140bdf6d0,&local_a28);
        uVar33 = (uint)uStack_970;
        uVar36 = uStack_970._4_4_;
        lVar29 = CONCAT44(uStack_970._4_4_,(uint)uStack_970);
        if (cVar6 != '\0') {
          cVar6 = FUN_1404f4e20();
          uVar34 = uVar28;
          uVar33 = (uint)uStack_970;
          uVar36 = uStack_970._4_4_;
          lVar29 = CONCAT44(uStack_970._4_4_,(uint)uStack_970);
          if (cVar6 == '\0') goto LAB_1401334a6;
        }
        uStack_970 = lVar29;
        uVar34 = uVar32;
        if (cVar7 != '\0') {
          uStack_970 = CONCAT44(uVar36,uVar33);
          uVar34 = 0x2a;
        }
      }
      else {
        puVar12 = param_1;
        if (0xf < uVar21) {
          puVar12 = (undefined8 *)*param_1;
        }
        if ((lVar29 == 0x19) &&
           (iVar8 = FUN_1409d1dc0(puVar12,"removeAutoStartCompletely",0x19), iVar8 == 0)) {
          FUN_1404f34f0();
          goto LAB_140131b65;
        }
        puVar12 = param_1;
        if (0xf < uVar21) {
          puVar12 = (undefined8 *)*param_1;
        }
        if ((lVar29 == 0x13) &&
           (iVar8 = FUN_1409d1dc0(puVar12,"installAutoStartKey",0x13), iVar8 == 0)) {
          pppppppuVar25 = local_860;
          if (0xf < local_848) {
            pppppppuVar25 = local_860[0];
          }
          cVar6 = FUN_1404f3550(1,bVar35,pppppppuVar25);
          uVar34 = (ulonglong)(-(uint)(cVar6 != '\0') & 0x2a);
        }
        else {
          puVar12 = param_1;
          if (0xf < uVar21) {
            puVar12 = (undefined8 *)*param_1;
          }
          if ((lVar29 == 0x12) &&
             (iVar8 = FUN_1409d1dc0(puVar12,"installScreensaver",0x12), iVar8 == 0)) {
            cVar6 = FUN_1404f4e20();
            uVar34 = (ulonglong)(-(uint)(cVar6 != '\0') & 0x2a);
          }
          else {
            puVar12 = param_1;
            if (0xf < uVar21) {
              puVar12 = (undefined8 *)*param_1;
            }
            if ((lVar29 == 0x16) &&
               (iVar8 = FUN_1409d1dc0(puVar12,"fixFirewallPermissions",0x16), iVar8 == 0)) {
              local_a18 = 0;
              pppppppuStack_a20 = (undefined8 *******)0x0;
              local_a28 = (undefined8 *******)0x0;
              local_a10 = 0xf;
              FUN_14014a790(&local_res10,&local_a28,1,0);
              uVar34 = 0x2a;
              if (0xf < local_a10) {
                uVar21 = local_a10 + 1;
                pppppppuVar25 = local_a28;
                if (0xfff < uVar21) {
                  pppppppuVar25 = (undefined8 *******)local_a28[-1];
                  if (0x1f < (ulonglong)((longlong)local_a28 + (-8 - (longlong)pppppppuVar25)))
                  goto LAB_1401347d4;
                  uVar21 = local_a10 + 0x28;
                }
                thunk_FUN_14098ca30(pppppppuVar25,uVar21);
              }
            }
            else {
              puVar12 = param_1;
              if (0xf < uVar21) {
                puVar12 = (undefined8 *)*param_1;
              }
              if ((lVar29 == 0xc) && (iVar8 = FUN_1409d1dc0(puVar12,"batchcompile",0xc), iVar8 == 0)
                 ) {
                FUN_1404a5590();
                local_9f8 = 8;
                local_a08 = (undefined8 *******)0x7463656a6f72702d;
                lStack_a00 = 0;
                uStack_9f0 = 0xf;
                cVar6 = FUN_140112aa0(&DAT_140bdf6d0,&local_a08);
                if (cVar6 == '\0') {
                  local_a18 = 9;
                  local_a28 = (undefined8 *******)0x657275747865742d;
                  local_a10 = 0xf;
                  pppppppuStack_a20 = (undefined8 *******)0x73;
                  cVar6 = FUN_140112aa0(&DAT_140bdf6d0,&local_a28);
                  if (cVar6 != '\0') {
                    local_a18 = 9;
                    local_a10 = 0xf;
                    local_a28 = (undefined8 *******)0x657275747865742d;
                    pppppppuStack_a20 = (undefined8 *******)0x73;
                    FUN_140137060(0x7865742d,local_7a8,&local_a28);
                    if (1 < local_798) {
                      pppppppcVar16 = (char *******)local_7a8;
                      if (0xf < local_790) {
                        pppppppcVar16 = local_7a8[0];
                      }
                      if (*(char *)pppppppcVar16 == '\"') {
                        uVar21 = local_798 - 1;
                        pppppppcVar16 = (char *******)local_7a8;
                        if (0xf < local_790) {
                          pppppppcVar16 = local_7a8[0];
                        }
                        if (*(char *)((longlong)pppppppcVar16 + uVar21) == '\"') {
                          local_9f8 = 0;
                          uStack_9f0 = 0;
                          uVar28 = local_798 - 2;
                          if (uVar21 < local_798 - 2) {
                            uVar28 = uVar21;
                          }
                          local_a08 = (undefined8 *******)0x0;
                          lStack_a00 = 0;
                          pppppppcVar16 = (char *******)local_7a8;
                          if (0xf < local_790) {
                            pppppppcVar16 = local_7a8[0];
                          }
                          FUN_1400718b0(&local_a08,(char *)((longlong)pppppppcVar16 + 1),uVar28);
                          FUN_140049b10(local_7a8,&local_a08);
                          FUN_140072260(&local_a08);
                        }
                      }
                    }
                    puVar12 = (undefined8 *)FUN_14000d3a0(&local_9e8,local_7a8);
                    if (7 < (ulonglong)puVar12[3]) {
                      puVar12 = (undefined8 *)*puVar12;
                    }
                    uVar13 = FUN_140989830(puVar12);
                    local_a18 = 0;
                    local_a10 = 0;
                    local_a28 = (undefined8 *******)0x0;
                    pppppppuStack_a20 = (undefined8 *******)0x0;
                    FUN_140071710(&local_a28,puVar12,uVar13);
                    FUN_140068de0(&local_988,&local_a28);
                    if (7 < local_a10) {
                      FUN_140071e30(&local_a28,local_a28);
                    }
                    FUN_140071e80(&local_9e8);
                    plVar14 = (longlong *)FUN_140017f80(&local_9a8,&local_988);
                    local_910 = plVar14[1];
                    pppppppuVar25 = (undefined8 *******)*plVar14;
                    *plVar14 = 0;
                    plVar14[1] = 0;
                    local_918 = pppppppuVar25;
                    uVar13 = FUN_140017f80(local_7e8,&local_988);
                    FUN_140068be0(&local_9a8,uVar13);
                    pppppppuVar23 = local_9a8;
                    while (pppppppuVar25 != pppppppuVar23) {
                      FUN_140071c40(&local_a28,pppppppuVar25 + 4);
                      cVar6 = FUN_14012cf10(&local_a28);
                      if ((cVar6 != '\0') && (cVar6 = FUN_14000e3b0(&local_a28), cVar6 != '\0')) {
                        uVar13 = FUN_14000dc90(&local_a28,&local_9e8);
                        FUN_14000dab0(uVar13,&local_9c8);
                        FUN_140071e80(&local_9e8);
                        pppppppuVar25 = &local_9c8;
                        if (0xf < local_9b0) {
                          pppppppuVar25 = local_9c8;
                        }
                        iVar8 = FUN_14096e390(pppppppuVar25,".tex" /*DAT_140ab58bc*/);
                        if (iVar8 == 0) {
LAB_140132505:
                          FUN_14000d0f0(local_788,".tex" /*DAT_140ab58bc*/);
                          FUN_14006dc70(&local_a28);
                          uVar21 = local_770;
                          lVar29 = local_778;
                          pppppppsVar4 = local_788[0];
                          if (local_778 != 0) {
                            pppppppsVar17 = (short *******)local_788;
                            if (7 < local_770) {
                              pppppppsVar17 = local_788[0];
                            }
                            if (*(short *)pppppppsVar17 != 0x2e) {
                              FUN_1400713f0(&local_a28,0x2e);
                            }
                          }
                          pppppppsVar17 = (short *******)local_788;
                          if (7 < uVar21) {
                            pppppppsVar17 = pppppppsVar4;
                          }
                          FUN_1400719c0(&local_a28,pppppppsVar17,lVar29);
                          FUN_140071e80(local_788);
                          puVar12 = (undefined8 *)FUN_14000dab0(&local_a28,&local_9e8);
                          if (0xf < (ulonglong)puVar12[3]) {
                            puVar12 = (undefined8 *)*puVar12;
                          }
                          FUN_1404a6410(0,puVar12,0,1);
                          FUN_140072260(&local_9e8);
                        }
                        else {
                          pppppppuVar25 = &local_9c8;
                          if (0xf < local_9b0) {
                            pppppppuVar25 = local_9c8;
                          }
                          iVar8 = FUN_14096e390(pppppppuVar25,".tex-json");
                          if (iVar8 == 0) goto LAB_140132505;
                        }
                        FUN_140072260(&local_9c8);
                      }
                      FUN_140071e80(&local_a28);
                      FUN_140068710(&local_918);
                      pppppppuVar25 = local_918;
                    }
                    FUN_140068c90(&local_9a8);
                    FUN_140068c90(&local_918);
                    FUN_140068c90(&local_988);
                    FUN_140072260(local_7a8);
                  }
                }
                else {
                  local_a08 = (undefined8 *******)0x7463656a6f72702d;
                  local_9f8 = 8;
                  uStack_9f0 = 0xf;
                  lStack_a00 = 0;
                  FUN_140137060(0,local_7c8,&local_a08);
                  if (1 < local_7b8) {
                    pppppppcVar16 = (char *******)local_7c8;
                    if (0xf < local_7b0) {
                      pppppppcVar16 = local_7c8[0];
                    }
                    if (*(char *)pppppppcVar16 == '\"') {
                      uVar21 = local_7b8 - 1;
                      pppppppcVar16 = (char *******)local_7c8;
                      if (0xf < local_7b0) {
                        pppppppcVar16 = local_7c8[0];
                      }
                      if (*(char *)((longlong)pppppppcVar16 + uVar21) == '\"') {
                        local_9f8 = 0;
                        uStack_9f0 = 0;
                        uVar28 = local_7b8 - 2;
                        if (uVar21 < local_7b8 - 2) {
                          uVar28 = uVar21;
                        }
                        local_a08 = (undefined8 *******)0x0;
                        lStack_a00 = 0;
                        pppppppcVar16 = (char *******)local_7c8;
                        if (0xf < local_7b0) {
                          pppppppcVar16 = local_7c8[0];
                        }
                        FUN_1400718b0(&local_a08,(char *)((longlong)pppppppcVar16 + 1),uVar28);
                        FUN_140049b10(local_7c8,&local_a08);
                        FUN_140072260(&local_a08);
                      }
                    }
                  }
                  puVar12 = (undefined8 *)FUN_14000d3a0(&local_9e8,local_7c8);
                  if (7 < (ulonglong)puVar12[3]) {
                    puVar12 = (undefined8 *)*puVar12;
                  }
                  uVar13 = FUN_140989830(puVar12);
                  local_a18 = 0;
                  local_a10 = 0;
                  local_a28 = (undefined8 *******)0x0;
                  pppppppuStack_a20 = (undefined8 *******)0x0;
                  FUN_140071710(&local_a28,puVar12,uVar13);
                  FUN_140068de0(&local_988,&local_a28);
                  if (7 < local_a10) {
                    FUN_140071e30(&local_a28,local_a28);
                  }
                  FUN_140071e80(&local_9e8);
                  plVar14 = (longlong *)FUN_140017f80(&local_9a8,&local_988);
                  local_910 = plVar14[1];
                  pppppppuVar25 = (undefined8 *******)*plVar14;
                  *plVar14 = 0;
                  plVar14[1] = 0;
                  local_918 = pppppppuVar25;
                  uVar13 = FUN_140017f80(local_7e8,&local_988);
                  FUN_140068be0(&local_9a8,uVar13);
                  pppppppuVar23 = local_9a8;
                  while (pppppppuVar25 != pppppppuVar23) {
                    FUN_140071c40(&local_9c8,pppppppuVar25 + 4);
                    cVar6 = FUN_14012cf10(&local_9c8);
                    if (cVar6 != '\0') {
                      plVar14 = (longlong *)FUN_1400711f0(&local_9c8,&local_9e8);
                      local_a08 = (undefined8 *******)*plVar14;
                      lStack_a00 = plVar14[1];
                      local_9f8 = plVar14[2];
                      uStack_9f0 = plVar14[3];
                      plVar14[2] = 0;
                      plVar14[3] = 7;
                      *(undefined2 *)plVar14 = 0;
                      FUN_140071e80(&local_9e8);
                      puVar12 = (undefined8 *)FUN_14000dab0(&local_a08,local_8f8);
                      if (0xf < (ulonglong)puVar12[3]) {
                        puVar12 = (undefined8 *)*puVar12;
                      }
                      iVar8 = FUN_14096e390(puVar12,"project.json");
                      FUN_140072260(local_8f8);
                      if (7 < uStack_9f0) {
                        FUN_140071e30(&local_a08,local_a08);
                      }
                      if (iVar8 == 0) {
                        local_a18 = 0;
                        pppppppuVar25 = &local_9c8;
                        if (7 < local_9b0) {
                          pppppppuVar25 = local_9c8;
                        }
                        local_a10 = 0;
                        local_a28 = (undefined8 *******)0x0;
                        pppppppuStack_a20 = (undefined8 *******)0x0;
                        uVar13 = FUN_140989830(pppppppuVar25);
                        FUN_140071710(&local_a28,pppppppuVar25,uVar13);
                        FUN_1404ce0d0(local_768,&local_a28);
                        if (7 < local_a10) {
                          FUN_140071e30(&local_a28,local_a28);
                        }
                        if ((local_73f != '\0') && (local_73c == 1)) {
                          uStack_968 = 0;
                          uStack_964 = 0;
                          local_978 = (undefined8 *)0x0;
                          uStack_970._0_4_ = 0;
                          uStack_970._4_4_ = 0;
                          cVar6 = FUN_1402075c0(local_768,&local_978);
                          if (cVar6 == '\0') {
                            if (local_978 != (undefined8 *)0x0) {
                              puVar12 = (undefined8 *)CONCAT44(uStack_970._4_4_,(uint)uStack_970);
                              for (puVar20 = local_978; puVar20 != puVar12; puVar20 = puVar20 + 4) {
                                FUN_140072260(puVar20);
                              }
                              FUN_140057010(&local_978,local_978,
                                            CONCAT44(uStack_964,uStack_968) - (longlong)local_978 >>
                                            5);
                              uStack_968 = 0;
                              uStack_964 = 0;
                              local_978 = (undefined8 *)0x0;
                              uStack_970._0_4_ = 0;
                              uStack_970._4_4_ = 0;
                            }
                          }
                          else {
                            puVar12 = (undefined8 *)FUN_14000dab0(&local_9c8,&local_9e8);
                            if (0xf < (ulonglong)puVar12[3]) {
                              puVar12 = (undefined8 *)*puVar12;
                            }
                            FUN_1402533a0("found scene project: %s\n",puVar12);
                            FUN_140072260(&local_9e8);
                            puVar12 = (undefined8 *)CONCAT44(uStack_970._4_4_,(uint)uStack_970);
                            for (puVar20 = local_978; puVar20 != puVar12; puVar20 = puVar20 + 4) {
                              puVar15 = puVar20;
                              if (0xf < (ulonglong)puVar20[3]) {
                                puVar15 = (undefined8 *)*puVar20;
                              }
                              FUN_1402533a0("found dependency: %s\n",puVar15);
                              FUN_14000d3a0(&local_a08,puVar20);
                              cVar6 = FUN_14000e3b0(&local_a08);
                              if (cVar6 != '\0') {
                                uVar13 = FUN_14000dc90(&local_a08,&local_9e8);
                                FUN_14000dab0(uVar13,&local_a28);
                                FUN_140071e80(&local_9e8);
                                pppppppuVar25 = &local_a28;
                                if (0xf < local_a10) {
                                  pppppppuVar25 = local_a28;
                                }
                                iVar8 = FUN_14096e390(pppppppuVar25,".tex" /*DAT_140ab58bc*/);
                                if (iVar8 == 0) {
                                  puVar15 = (undefined8 *)FUN_14000dab0(&local_a08,&local_9e8);
                                  if (0xf < (ulonglong)puVar15[3]) {
                                    puVar15 = (undefined8 *)*puVar15;
                                  }
                                  FUN_1404a6410(0,puVar15,0);
                                  FUN_140072260(&local_9e8);
                                }
                                else {
                                  pppppppuVar25 = &local_a28;
                                  if (0xf < local_a10) {
                                    pppppppuVar25 = local_a28;
                                  }
                                  iVar8 = FUN_14096e390(pppppppuVar25,".mdl" /*DAT_140ac9768*/);
                                  if (iVar8 == 0) {
                                    local_8d8 = (undefined8 *******)CONCAT71(local_8d8._1_7_,1);
                                    uStack_8c8 = 0;
                                    local_8d0 = (undefined4 *)0x0;
                                    local_8c0 = 0;
                                    uStack_8be = 0;
                                    uStack_8bc = 0;
                                    uStack_8ba = 0;
                                    uStack_8b8 = 0xf;
                                    uStack_8b2 = 0;
                                    puVar15 = (undefined8 *)FUN_14000dab0(&local_a08,&local_9e8);
                                    if (0xf < (ulonglong)puVar15[3]) {
                                      puVar15 = (undefined8 *)*puVar15;
                                    }
                                    local_a48 = &local_8d8;
                                    FUN_1404a7250(0,0,puVar15,0);
                                    FUN_140072260(&local_9e8);
                                    if (0xf < CONCAT26(uStack_8b2,uStack_8b8)) {
                                      FUN_140072220(&local_8d0,local_8d0);
                                    }
                                  }
                                }
                                FUN_140072260(&local_a28);
                              }
                              FUN_140071e80(&local_a08);
                            }
                            FUN_14004ffe0(&local_978);
                          }
                        }
                        FUN_14001bdd0(local_768);
                      }
                    }
                    FUN_140071e80(&local_9c8);
                    FUN_140068710(&local_918);
                    pppppppuVar25 = local_918;
                  }
                  FUN_140068c90(&local_9a8);
                  FUN_140068c90(&local_918);
                  FUN_140068c90(&local_988);
                  FUN_140072260(local_7c8);
                }
              }
              else {
                puVar12 = param_1;
                if (0xf < uVar21) {
                  puVar12 = (undefined8 *)*param_1;
                }
                if ((lVar29 == 0xf) &&
                   (iVar8 = FUN_1409d1dc0(puVar12,"steamscreenshot",0xf), iVar8 == 0)) {
                  local_998 = 0;
                  local_9a8 = (undefined8 *******)0x0;
                  pppppppuStack_9a0 = (undefined8 *******)0x0;
                  FUN_140128eb0(&local_9a8,&local_res18,&local_res10);
                  uVar34 = 1;
                  local_878[0] = 1;
                  if (local_9a8 == pppppppuStack_9a0) {
LAB_140132904:
                    if (local_9a8 != (undefined8 *******)0x0) {
                      uVar21 = local_998 - (longlong)local_9a8;
                      pppppppuVar25 = local_9a8;
                      if (0xfff < uVar21) {
                        pppppppuVar25 = (undefined8 *******)local_9a8[-1];
                        if (0x1f < (ulonglong)((longlong)local_9a8 + (-8 - (longlong)pppppppuVar25))
                           ) goto LAB_1401347d4;
                        uVar21 = uVar21 + 0x27;
                      }
                      thunk_FUN_14098ca30(pppppppuVar25,uVar21);
                    }
                  }
                  else {
                    uVar13 = FUN_14000e700(local_8f8);
                    uVar13 = FUN_14000e580(uVar13);
                    uVar13 = FUN_14000d880(uVar13,"sc.flag");
                    FUN_140071c40(&local_9e8,uVar13);
                    FUN_140071e80(local_8f8);
                    cVar6 = FUN_14000e6a0(&local_9e8);
                    if (cVar6 == '\0') {
                      plVar14 = (longlong *)FUN_1400fc210();
                      cVar6 = (**(code **)(*plVar14 + 8))(plVar14);
                      if (cVar6 == '\0') {
                        (**(code **)*plVar14)(plVar14,1);
                        FUN_140071e80(&local_9e8);
                        goto LAB_140132904;
                      }
                      local_8a0 = (undefined8 *******)0x0;
                      (*(code *)((undefined8 *)*plVar14)[0x2f])(plVar14,&local_8d8);
                      local_948 = 0;
                      local_a18 = 0xc;
                      local_958 = 0;
                      lStack_950 = 0;
                      local_a10 = 0xf;
                      local_a28 = (undefined8 *******)0x6f68736b726f772d;
                      pppppppuStack_a20 = (undefined8 *******)0x73646970;
                      cVar6 = FUN_140112aa0(&DAT_140bdf6d0,&local_a28);
                      if (cVar6 != '\0') {
                        local_9f8 = 0;
                        uStack_9f0 = 0;
                        local_a08 = (undefined8 *******)0x0;
                        lStack_a00 = 0;
                        uVar36 = FUN_1400718b0(&local_a08,"-workshopids" /*DAT_140ad1a38*/,0xc);
                        FUN_140137060(uVar36,local_8f8,&local_a08);
                        FUN_14000c760(&local_a28,local_8f8,0x23);
                        pppppppuVar23 = pppppppuStack_a20;
                        for (pppppppuVar25 = local_a28; pppppppuVar25 != pppppppuVar23;
                            pppppppuVar25 = pppppppuVar25 + 4) {
                          FUN_140071d20(&local_a08,pppppppuVar25);
                          pppppppuVar22 = &local_a08;
                          if (0xf < uStack_9f0) {
                            pppppppuVar22 = local_a08;
                          }
                          local_res20 = FUN_14096e140(pppppppuVar22,0,10);
                          if (local_res20 != 0) {
                            FUN_140048860(&local_958,&local_res20);
                          }
                          FUN_140072260(&local_a08);
                        }
                        FUN_14004ffe0(&local_a28);
                        FUN_140072260(local_8f8);
                        uVar34 = local_878[0];
                      }
                      local_a48 = (undefined8 *******)&local_958;
                      cVar6 = (**(code **)(*plVar14 + 0x168))
                                        (plVar14,local_9a8,local_res18 & 0xffffffff,local_res10);
                      if ((cVar6 != '\0') && (local_958 != lStack_950)) {
                        FUN_14011ca30(plVar14,"SCREENSHOT_WITH_WORKSHOP_WALLPAPER");
                      }
                      (**(code **)(*plVar14 + 0x10))(plVar14);
                      (**(code **)*plVar14)(plVar14,uVar34 & 0xffffffff);
                      local_a40 = 5;
                      local_a48 = (undefined8 *******)0x0;
                      ShellExecuteW(0,0,L"steam://open/screenshots",0);
                      FUN_140045d70(&local_958);
                    }
                    else {
                      local_a18 = 0;
                      pppppppuStack_a20 = (undefined8 *******)0x0;
                      local_a28 = (undefined8 *******)0x0;
                      local_a10 = 7;
                      local_9b8 = 0;
                      local_9c8 = (undefined8 *******)0x0;
                      uStack_9c0 = 0;
                      local_9b0 = 0;
                      FUN_140071710(&local_9c8,L"Wallpaper Engine Screenshot.png",0x1f);
                      cVar6 = FUN_140147f40(0,&local_a28,&local_9c8,0);
                      if (7 < local_9b0) {
                        FUN_140071e30(&local_9c8,local_9c8);
                      }
                      if (cVar6 != '\0') {
                        local_928 = 0;
                        local_948 = 0;
                        local_938 = (undefined8 *****)0x0;
                        lStack_930 = 0;
                        local_958 = 0;
                        lStack_950 = 0;
                        FUN_140048c80(&local_938,
                                      (longlong)
                                      ((int)((ulonglong)
                                             ((longlong)pppppppuStack_9a0 - (longlong)local_9a8) / 3
                                            ) << 2));
                        uVar21 = uVar28;
                        uVar34 = uVar28;
                        if ((ulonglong)((longlong)pppppppuStack_9a0 - (longlong)local_9a8) / 3 != 0)
                        {
                          do {
                            iVar30 = (int)uVar21;
                            uVar33 = (int)uVar34 + 1;
                            iVar8 = (int)uVar28;
                            *(undefined1 *)((longlong)iVar8 + (longlong)local_938) =
                                 *(undefined1 *)((longlong)iVar30 + 2 + (longlong)local_9a8);
                            *(undefined1 *)((longlong)iVar8 + 1 + (longlong)local_938) =
                                 *(undefined1 *)((longlong)iVar30 + 1 + (longlong)local_9a8);
                            *(undefined1 *)((longlong)iVar8 + 2 + (longlong)local_938) =
                                 *(undefined1 *)((longlong)iVar30 + (longlong)local_9a8);
                            uVar28 = (ulonglong)(iVar8 + 4);
                            *(undefined1 *)((longlong)iVar8 + 3 + (longlong)local_938) = 0xff;
                            uVar21 = (ulonglong)(iVar30 + 3);
                            uVar34 = (ulonglong)uVar33;
                          } while ((ulonglong)(longlong)(int)uVar33 <
                                   (ulonglong)((longlong)pppppppuStack_9a0 - (longlong)local_9a8) /
                                   3);
                        }
                        local_978 = (undefined8 *)CONCAT44((uint)local_res18,(undefined4)local_978);
                        uStack_970._0_4_ = local_res10;
                        uStack_970._4_4_ = 0;
                        uStack_968 = 0;
                        local_978 = (undefined8 *)CONCAT71(local_978._1_7_,1);
                        cVar6 = FUN_140258550(&local_978,&local_938,&local_958);
                        if (cVar6 != '\0') {
                          pppppppuVar25 = &local_a28;
                          if (7 < local_a10) {
                            pppppppuVar25 = local_a28;
                          }
                          FUN_1400149d0(pppppppuVar25,local_958,(int)lStack_950 - (int)local_958);
                        }
                        plVar14 = (longlong *)FUN_1400fc210();
                        cVar6 = (**(code **)(*plVar14 + 8))(plVar14);
                        if (cVar6 != '\0') {
                          local_8a0 = (undefined8 *******)0x0;
                          (**(code **)(*plVar14 + 0x178))(plVar14,&local_8d8);
                          FUN_14011ca30(plVar14,"SCREENSHOT_WITH_WORKSHOP_WALLPAPER");
                          (**(code **)(*plVar14 + 0x10))(plVar14);
                        }
                        (**(code **)*plVar14)(plVar14,1);
                        FUN_14004fe70(&local_958);
                        FUN_14004fe70(&local_938);
                      }
                      FUN_140071e80(&local_a28);
                    }
                    FUN_140071e80(&local_9e8);
                    FUN_14004fe70(&local_9a8);
                    uVar34 = uVar32;
                  }
                }
                else {
                  puVar12 = param_1;
                  if (0xf < uVar21) {
                    puVar12 = (undefined8 *)*param_1;
                  }
                  if ((lVar29 == 10) && (iVar8 = FUN_1409d1dc0(puVar12,"resetstats",10), iVar8 == 0)
                     ) {
                    plVar14 = (longlong *)FUN_1400fc210();
                    cVar6 = (**(code **)(*plVar14 + 8))(plVar14);
                    if (cVar6 == '\0') {
LAB_140132cfe:
                      (**(code **)*plVar14)(plVar14,1);
                      uVar34 = uVar28;
                    }
                    else {
                      (**(code **)(*plVar14 + 0x180))(plVar14);
                      (**(code **)(*plVar14 + 0x10))(plVar14);
                      (**(code **)*plVar14)(plVar14,1);
                    }
                  }
                  else {
                    puVar12 = param_1;
                    if (0xf < uVar21) {
                      puVar12 = (undefined8 *)*param_1;
                    }
                    if ((lVar29 == 0x10) &&
                       (iVar8 = FUN_1409d1dc0(puVar12,"getsteamlanguage",0x10), iVar8 == 0)) {
                      plVar14 = (longlong *)FUN_1400fc210();
                      cVar6 = (**(code **)(*plVar14 + 8))(plVar14);
                      if (cVar6 == '\0') {
LAB_140132cf9:
                        uVar28 = 0xffffffff;
                        goto LAB_140132cfe;
                      }
                      cVar6 = (**(code **)(*plVar14 + 0x30))(plVar14,0x1d);
                      if (cVar6 != '\0') {
                        FUN_140230fb0();
                      }
                      (**(code **)(*plVar14 + 0x98))(plVar14,&local_a08);
                      pppppppuVar25 = &local_a08;
                      if (0xf < uStack_9f0) {
                        pppppppuVar25 = local_a08;
                      }
                      uVar33 = FUN_140246ca0(pppppppuVar25);
                      (**(code **)(*plVar14 + 0x10))(plVar14);
                      FUN_140072260(&local_a08);
                      (**(code **)*plVar14)(plVar14,1);
                      uVar34 = (ulonglong)uVar33;
                    }
                    else {
                      puVar12 = param_1;
                      if (0xf < uVar21) {
                        puVar12 = (undefined8 *)*param_1;
                      }
                      if ((lVar29 == 0xf) &&
                         (iVar8 = FUN_1409d1dc0(puVar12,"steamtrackhours",0xf), iVar8 == 0)) {
                        plVar14 = (longlong *)FUN_1400fc210();
                        cVar6 = (**(code **)(*plVar14 + 8))(plVar14);
                        if (cVar6 == '\0') goto LAB_140132cf9;
                        local_res10 = CONCAT31(local_res10._1_3_,1);
                        local_8d8 = (undefined8 *******)&DAT_140ad1f88;
                        local_8d0 = &local_res10;
                        local_8a0 = &local_8d8;
                        FUN_1402346d0(extraout_XMM0_Da,&local_8d8);
                        cVar6 = FUN_140234750();
                        if (cVar6 != '\0') {
                          while ((char)local_res10 != '\0') {
                            Sleep(1000);
                          }
                        }
                        (**(code **)(*plVar14 + 0x10))(plVar14);
                        (**(code **)*plVar14)(plVar14,1);
                        uVar34 = 0x2a;
                      }
                      else {
                        cVar6 = FUN_140042480(param_1,"unpackProject");
                        if (cVar6 == '\0') {
                          cVar6 = FUN_140042480(param_1,"packProject");
                          if (cVar6 != '\0') {
                            local_9f8 = 0;
                            uStack_9f0 = 0;
                            local_a08 = (undefined8 *******)0x0;
                            lStack_a00 = 0;
                            FUN_1400718b0(&local_a08,"-i" /*DAT_140ad1a94*/,2);
                            cVar6 = FUN_140112aa0(&DAT_140bdf6d0,&local_a08);
                            if (cVar6 != '\0') {
                              local_9f8 = 0;
                              uStack_9f0 = 0;
                              local_a08 = (undefined8 *******)0x0;
                              lStack_a00 = 0;
                              FUN_1400718b0(&local_a08,"-o" /*DAT_140ad1a90*/,2);
                              cVar6 = FUN_140112aa0(&DAT_140bdf6d0,&local_a08);
                              if (cVar6 != '\0') {
                                local_9f8 = 0;
                                uStack_9f0 = 0;
                                local_a08 = (undefined8 *******)0x0;
                                lStack_a00 = 0;
                                uVar36 = FUN_1400718b0(&local_a08,"-i" /*DAT_140ad1a94*/,2);
                                uVar13 = FUN_140137060(uVar36,&local_9e8,&local_a08);
                                FUN_14000d3a0(&local_a08,uVar13);
                                FUN_140072260(&local_9e8);
                                local_a18 = 0;
                                local_a10 = 0;
                                local_a28 = (undefined8 *******)0x0;
                                pppppppuStack_a20 = (undefined8 *******)0x0;
                                uVar36 = FUN_1400718b0(&local_a28,"-o" /*DAT_140ad1a90*/,2);
                                uVar13 = FUN_140137060(uVar36,&local_9e8,&local_a28);
                                FUN_14000d3a0(local_8f8,uVar13);
                                FUN_140072260(&local_9e8);
                                cVar6 = FUN_14000e640(&local_a08);
                                if (cVar6 == '\0') {
                                  uVar32 = 0xffffffff;
                                }
                                else {
                                  FUN_140071970(&local_9e8,"blobsSM40");
                                  local_9a8 = (undefined8 *******)&local_9e8;
                                  pppppppuStack_9a0 = &local_9c8;
                                  FUN_1400487b0(&local_8d8,&local_9a8);
                                  local_878[0] = 1;
                                  FUN_140013170(&local_9e8,0x20,1,thunk_FUN_140072260);
                                  local_948 = 0;
                                  local_988 = 0;
                                  local_980 = &PTR_PTR_140ba0760;
                                  local_958 = 0;
                                  lStack_950 = 0;
                                  FUN_140071c40(&local_9e8,&local_a08);
                                  FUN_140068de0(&local_918,&local_9e8);
                                  FUN_140071e80(&local_9e8);
                                  while (pppppppuVar25 = local_918,
                                        local_918 != (undefined8 *******)0x0) {
                                    FUN_140071c40(&local_9c8,local_918 + 4);
                                    cVar6 = FUN_14000e3b0(&local_9c8);
                                    if (cVar6 == '\0') {
LAB_140133409:
                                      FUN_140071e80(&local_9c8);
                                      FUN_140068710(&local_918);
                                    }
                                    else {
                                      puVar18 = (undefined4 *)FUN_1400711f0(&local_9c8,&local_978);
                                      local_9e8._0_4_ = *puVar18;
                                      local_9e8._4_4_ = puVar18[1];
                                      uStack_9e0 = puVar18[2];
                                      uStack_9dc = puVar18[3];
                                      local_9d8 = puVar18[4];
                                      uStack_9d4 = puVar18[5];
                                      uStack_9d0 = puVar18[6];
                                      uStack_9cc = puVar18[7];
                                      *(undefined8 *)(puVar18 + 4) = 0;
                                      *(undefined8 *)(puVar18 + 6) = 7;
                                      *(undefined2 *)puVar18 = 0;
                                      FUN_140071e80(&local_978);
                                      uVar13 = FUN_14000dab0(&local_9e8,&local_9a8);
                                      plVar14 = (longlong *)
                                                FUN_140045e50(&local_8d8,&local_res10,uVar13);
                                      puVar3 = local_8d0;
                                      puVar18 = (undefined4 *)*plVar14;
                                      FUN_140072260(&local_9a8);
                                      FUN_140071e80(&local_9e8);
                                      if (puVar18 == puVar3) {
                                        uVar13 = FUN_14000dc90(&local_9c8,&local_9e8);
                                        FUN_14000dab0(uVar13,&local_a28);
                                        FUN_140071e80(&local_9e8);
                                        uVar21 = local_a10;
                                        lVar29 = local_a18;
                                        pppppppuVar25 = local_a28;
                                        pppppppuVar23 = &local_a28;
                                        if (0xf < local_a10) {
                                          pppppppuVar23 = local_a28;
                                        }
                                        if (local_a18 == 4) {
                                          iVar8 = FUN_1409d1dc0(pppppppuVar23,".mtl" /*DAT_140aca060*/,4);
                                          bVar35 = iVar8 == 0;
                                        }
                                        else {
                                          bVar35 = false;
                                        }
                                        pppppppuVar23 = &local_a28;
                                        if (0xf < uVar21) {
                                          pppppppuVar23 = pppppppuVar25;
                                        }
                                        uVar32 = uVar28;
                                        if ((lVar29 == 5) &&
                                           (iVar8 = FUN_1409d1dc0(pppppppuVar23,".json",5),
                                           iVar8 == 0)) {
                                          if (!bVar35) {
LAB_1401332ff:
                                            FUN_14000ec40(&local_938,&local_9c8,&local_a08,
                                                          &local_988);
                                            local_828 = 0;
                                            local_808 = 0;
                                            lStack_830 = 0;
                                            local_838 = 0;
                                            uStack_810 = 0;
                                            local_818 = 0;
                                            local_820 = 0xf;
                                            local_800 = 0xf;
                                            uVar13 = FUN_14000dab0(&local_9c8,&local_9e8);
                                            FUN_140049b10(&local_818,uVar13);
                                            FUN_140072260(&local_9e8);
                                            uVar13 = FUN_14000dab0(&local_938,&local_9e8);
                                            FUN_140049b10(&local_838,uVar13);
                                            FUN_140072260(&local_9e8);
                                            local_7f8 = FUN_14000ee30(&local_9c8);
                                            FUN_14000c3f0(&local_818,0x2f);
                                            FUN_14000c3f0(&local_838,0x2f);
                                            FUN_140046030(&local_958,&local_838);
                                            FUN_140072260(&local_818);
                                            FUN_140072260(&local_838);
                                            FUN_140071e80(&local_938);
                                          }
                                        }
                                        else {
                                          do {
                                            if ((bVar35) ||
                                               (cVar6 = FUN_140042480(&local_a28,
                                                                      (&PTR_DAT_140abab70)[uVar32]),
                                               cVar6 != '\0')) {
                                              bVar35 = true;
                                            }
                                            uVar32 = uVar32 + 1;
                                            uVar34 = uVar28;
                                          } while (uVar32 != 9);
                                          do {
                                            if ((bVar35) ||
                                               (cVar6 = FUN_140042480(&local_a28,
                                                                      (&PTR_DAT_140ababc0)[uVar34]),
                                               cVar6 != '\0')) {
                                              bVar35 = true;
                                            }
                                            uVar34 = uVar34 + 1;
                                          } while (uVar34 != 0xc);
                                          if (!bVar35) {
                                            pppppppuVar23 = &local_a28;
                                            if (0xf < uVar21) {
                                              pppppppuVar23 = pppppppuVar25;
                                            }
                                            if ((lVar29 != 4) ||
                                               (iVar8 = FUN_1409d1dc0(pppppppuVar23,".mp4" /*DAT_140ab40ac*/,4
                                                                     ), iVar8 != 0))
                                            goto LAB_1401332ff;
                                          }
                                        }
                                        FUN_140072260(&local_a28);
                                        goto LAB_140133409;
                                      }
                                      *(undefined1 *)((longlong)pppppppuVar25 + 100) = 0;
                                      FUN_140071e80();
                                      FUN_140068710(&local_918);
                                    }
                                  }
                                  FUN_140068c90(&local_918);
                                  uVar13 = FUN_14000dab0(local_8f8,&local_9e8);
                                  uVar19 = FUN_140046230(&local_a28,&local_958);
                                  cVar6 = FUN_14020a660(uVar19,uVar13,"PKGV0024");
                                  FUN_140072260(&local_9e8);
                                  if (cVar6 == '\0') {
                                    uVar28 = local_878[0] & 0xffffffff;
                                  }
                                  FUN_14004f480(&local_958);
                                  FUN_140045d70(&local_8c0);
                                  FUN_1400463b0(&local_8d0);
                                  uVar32 = uVar28;
                                }
                                ppppppuVar24 = local_8f8;
LAB_140133494:
                                FUN_140071e80(ppppppuVar24);
                                pppppppuVar25 = &local_a08;
                                goto LAB_14013349e;
                              }
                            }
                            goto LAB_14013464a;
                          }
                          cVar6 = FUN_140042480(param_1,"convertProjectToMobile");
                          if (cVar6 == '\0') {
                            cVar6 = FUN_140042480(param_1,"prepareSharedMobileAssets");
                            if (cVar6 != '\0') {
                              local_9f8 = 0;
                              uStack_9f0 = 0;
                              local_a08 = (undefined8 *******)0x0;
                              lStack_a00 = 0;
                              FUN_1400718b0(&local_a08,"-i" /*DAT_140ad1a94*/,2);
                              cVar6 = FUN_140112aa0(&DAT_140bdf6d0,&local_a08);
                              if (cVar6 != '\0') {
                                uVar13 = FUN_140071970(local_898,"-o" /*DAT_140ad1a90*/);
                                cVar6 = FUN_140112aa0(&DAT_140bdf6d0,uVar13);
                                if (cVar6 != '\0') {
                                  uVar13 = FUN_140071970(local_898,"-i" /*DAT_140ad1a94*/);
                                  uVar13 = FUN_140137060(extraout_XMM0_Da_00,&local_9e8,uVar13);
                                  FUN_14000d3a0(&local_a08,uVar13);
                                  FUN_140072260(&local_9e8);
                                  uVar13 = FUN_140071970(local_898,"-o" /*DAT_140ad1a90*/);
                                  uVar13 = FUN_140137060(extraout_XMM0_Da_01,&local_9e8,uVar13);
                                  FUN_14000d3a0(&local_938,uVar13);
                                  FUN_140072260(&local_9e8);
                                  uVar13 = FUN_14012cfa0(&local_9e8,&local_a08);
                                  FUN_140071ba0(&local_a08,uVar13);
                                  FUN_140071e80(&local_9e8);
                                  uVar13 = FUN_14012cfa0(&local_9e8,&local_938);
                                  FUN_140071ba0(&local_938,uVar13);
                                  FUN_140071e80(&local_9e8);
                                  uVar13 = FUN_140071970(local_898,"-clearUnused");
                                  cVar6 = FUN_140112aa0(&DAT_140bdf6d0,uVar13);
                                  local_res10 = CONCAT31(local_res10._1_3_,cVar6);
                                  cVar7 = FUN_14000e640(&local_938);
                                  if (cVar7 != '\0') {
                                    FUN_14000ea80(&local_938);
                                  }
                                  cVar7 = FUN_14000e880(&local_a08,&local_938,0x12);
                                  if (cVar7 != '\0') {
                                    FUN_140071970(local_768,"effects" /*DAT_140ac7ca0*/);
                                    FUN_140071970(local_748,"presets");
                                    FUN_140071970(local_728,"particles");
                                    FUN_140071970(local_708,"scenes");
                                    FUN_140071970(local_6e8,"zcompat");
                                    local_9a8 = local_768;
                                    pppppppuStack_9a0 = (undefined8 *******)local_6c8;
                                    FUN_1400487b0(&local_838,&local_9a8);
                                    FUN_140013170(local_768,0x20,5,thunk_FUN_140072260);
                                    FUN_140071970(local_768,"blobsSM40");
                                    FUN_140071970(local_748,"HLSL" /*DAT_140ad1b1c*/);
                                    FUN_140071970(local_728,"lut" /*DAT_140ad1b18*/);
                                    local_9a8 = local_768;
                                    pppppppuStack_9a0 = local_708;
                                    FUN_1400487b0(&local_8d8,&local_9a8);
                                    FUN_140013170(local_768,0x20,3,thunk_FUN_140072260);
                                    FUN_140071c40(&local_9e8,&local_938);
                                    FUN_140068de0(&local_988,&local_9e8);
                                    FUN_140071e80(&local_9e8);
                                    local_878[0] = 1;
                                    while (lVar29 = CONCAT44(uStack_984,local_988), lVar29 != 0) {
                                      puVar20 = (undefined8 *)
                                                FUN_1400711f0(lVar29 + 0x20,&local_9e8);
                                      puVar12 = puVar20;
                                      if (7 < (ulonglong)puVar20[3]) {
                                        puVar12 = (undefined8 *)*puVar20;
                                      }
                                      uVar21 = puVar20[2];
                                      uVar36 = FUN_1408e6690();
                                      FUN_14006fd80(&local_958,&local_res18);
                                      if (uVar21 != 0) {
                                        if (0x7fffffff < uVar21) goto LAB_1401347db;
                                        local_a48 = (undefined8 *******)
                                                    ((ulonglong)local_a48 & 0xffffffff00000000);
                                        uVar13 = FUN_1408e6710(uVar36,puVar12,uVar21 & 0xffffffff,0)
                                        ;
                                        iVar8 = FUN_1400703c0(uVar13);
                                        FUN_1400700c0(&local_958,(longlong)iVar8,0);
                                        FUN_1400498e0(&local_958);
                                        local_a48 = (undefined8 *******)
                                                    CONCAT44(local_a48._4_4_,iVar8);
                                        uVar13 = FUN_1408e6710(uVar36,puVar12,uVar21 & 0xffffffff);
                                        FUN_1400703c0(uVar13);
                                        cVar6 = (char)local_res10;
                                      }
                                      FUN_140071e80(&local_9e8);
                                      lVar11 = lStack_830;
                                      puVar18 = local_8d0;
                                      if ((int)(*(longlong *)(lVar29 + 0x50) -
                                                *(longlong *)(lVar29 + 0x48) >> 3) == 0) {
                                        if ((cVar6 != '\0') &&
                                           (plVar14 = (longlong *)
                                                      FUN_140045e50(&local_838,&local_res20,
                                                                    &local_958), *plVar14 != lVar11)
                                           ) {
LAB_140133c36:
                                          FUN_140071c40(&local_9e8,lVar29 + 0x20);
                                          FUN_14000ea80(&local_9e8);
                                          FUN_140071e80(&local_9e8);
                                          *(undefined1 *)(lVar29 + 100) = 0;
                                        }
                                      }
                                      else {
                                        plVar14 = (longlong *)
                                                  FUN_140045e50(&local_8d8,&local_918,&local_958);
                                        if ((undefined4 *)*plVar14 != puVar18) goto LAB_140133c36;
                                        FUN_140071c40(&local_9c8,lVar29 + 0x20);
                                        cVar6 = FUN_14000e3b0(&local_9c8);
                                        if (cVar6 != '\0') {
                                          FUN_14000ecf0(&local_9a8,&local_9c8,&local_938);
                                          uVar13 = FUN_14000e490(&local_9a8,&local_9e8);
                                          FUN_14000dab0(uVar13,&local_978);
                                          FUN_140071e80(&local_9e8);
                                          uVar13 = FUN_14000dc90(&local_9c8,&local_9e8);
                                          FUN_14000dab0(uVar13,&local_a28);
                                          FUN_140071e80(&local_9e8);
                                          cVar6 = FUN_140042480(&local_a28,".mtl" /*DAT_140aca060*/);
                                          cVar7 = FUN_140042480(&local_a28,".json");
                                          if (cVar7 == '\0') {
                                            lVar29 = 0;
                                            do {
                                              if ((cVar6 != '\0') ||
                                                 (cVar7 = FUN_140042480(&local_a28,
                                                                        (&PTR_DAT_140abab70)[lVar29]
                                                                       ), cVar7 != '\0')) {
                                                cVar6 = '\x01';
                                              }
                                              lVar29 = lVar29 + 1;
                                            } while (lVar29 != 9);
                                            lVar29 = 0;
                                            do {
                                              if ((cVar6 != '\0') ||
                                                 (cVar7 = FUN_140042480(&local_a28,
                                                                        (&PTR_DAT_140ababc0)[lVar29]
                                                                       ), cVar7 != '\0')) {
                                                cVar6 = '\x01';
                                              }
                                              lVar29 = lVar29 + 1;
                                            } while (lVar29 != 0xc);
                                            if (cVar6 == '\0') {
                                              cVar6 = FUN_140042480(&local_a28,".mp4" /*DAT_140ab40ac*/);
                                              goto LAB_140133d87;
                                            }
LAB_140133d89:
                                            FUN_14000ea30(&local_9c8);
                                          }
                                          else {
LAB_140133d87:
                                            if (cVar6 != '\0') goto LAB_140133d89;
                                            cVar6 = FUN_140042480(&local_a28,".tex" /*DAT_140ab58bc*/);
                                            if (cVar6 == '\0') {
                                              cVar6 = FUN_140042480(&local_978,"shaders");
                                              if ((cVar6 != '\0') &&
                                                 ((((cVar6 = FUN_140042480(&local_a28,".vert"),
                                                    cVar6 != '\0' ||
                                                    (cVar6 = FUN_140042480(&local_a28,".frag"),
                                                    cVar6 != '\0')) ||
                                                   (cVar6 = FUN_140042480(&local_a28,".geom"),
                                                   cVar6 != '\0')) ||
                                                  (cVar6 = FUN_140042480(&local_a28,".h" /*DAT_140ac7ccc*/),
                                                  cVar6 != '\0')))) {
                                                uVar13 = FUN_14000d7a0(&local_9c8);
                                                FUN_140014570(&local_9e8,uVar13);
                                                uVar13 = FUN_14000df80(&local_9c8,local_7e8);
                                                uVar13 = FUN_14000dab0(uVar13,local_8f8);
                                                FUN_140015f20(uVar13,&local_9e8);
                                                FUN_140072260(local_8f8);
                                                FUN_140071e80(local_7e8);
                                                uVar13 = FUN_14000d7a0(&local_9c8);
                                                FUN_140014830(uVar13,&local_9e8);
                                                FUN_140072260(&local_9e8);
                                              }
                                            }
                                            else {
                                              FUN_1402333c0(local_308);
                                              FUN_14004b800(local_768,local_878[0] & 0xffffffff);
                                              uVar13 = FUN_140042940(local_758,"-transcode -i \"");
                                              uVar19 = FUN_14000dab0(&local_9c8,local_8f8);
                                              uVar13 = FUN_140042de0(uVar13,uVar19);
                                              uVar13 = FUN_140042940(uVar13,"\" -o \"");
                                              uVar19 = FUN_14000dab0(&local_9c8,&local_9e8);
                                              uVar13 = FUN_140042de0(uVar13,uVar19);
                                              FUN_140042940(uVar13,"\" -f ETC2");
                                              FUN_140072260(&local_9e8);
                                              FUN_140072260(local_8f8);
                                              FUN_1402333c0(local_5d8);
                                              local_570 = 1;
                                              uVar13 = FUN_14004b680(local_768,&local_9e8);
                                              uVar13 = FUN_1400498e0(uVar13);
                                              FUN_1402334d0(local_5d8,"resourcecompiler64.exe",
                                                            uVar13);
                                              FUN_140072260(&local_9e8);
                                              cVar6 = FUN_140234470(local_5d8,300000,0);
                                              if (cVar6 == '\0') {
                                                FUN_140233440(local_5d8);
                                                FUN_14000c5e0(local_768);
                                                FUN_140233440(local_308);
                                                FUN_140072260(&local_a28);
                                                FUN_140072260(&local_978);
                                                FUN_140071e80(&local_9a8);
                                                FUN_140071e80(&local_9c8);
                                                FUN_140072260(&local_958);
                                                FUN_140068c90(&local_988);
                                                FUN_140018300(&local_8d8);
                                                FUN_140018300(&local_838);
                                                goto LAB_140134064;
                                              }
                                              FUN_140233440(local_5d8);
                                              FUN_14000c5e0(local_768);
                                              FUN_140233440(local_308);
                                            }
                                          }
                                          FUN_140072260(&local_a28);
                                          FUN_140072260(&local_978);
                                          FUN_140071e80(&local_9a8);
                                        }
                                        FUN_140071e80(&local_9c8);
                                      }
                                      FUN_140072260(&local_958);
                                      FUN_140068710(&local_988);
                                      cVar6 = (char)local_res10;
                                    }
                                    FUN_140068c90(&local_988);
                                    uVar32 = 0;
                                    FUN_140045d70(&local_8c0);
                                    FUN_1400463b0(&local_8d0);
                                    FUN_140045d70(&local_820);
                                    FUN_1400463b0(&lStack_830);
                                    ppppppuVar24 = &local_938;
                                    goto LAB_140133494;
                                  }
LAB_140134064:
                                  FUN_140071e80(&local_938);
                                  FUN_140071e80(&local_a08);
                                  uVar34 = 0xffffffff;
                                  goto LAB_1401334a6;
                                }
                              }
                              goto LAB_1401338c4;
                            }
                            cVar6 = FUN_140042480(param_1,"gifenc");
                            if (cVar6 == '\0') goto LAB_1401334a6;
                            uVar13 = FUN_140071970(local_898,"-f" /*DAT_140ad1b40*/);
                            cVar6 = FUN_140112aa0(&DAT_140bdf6d0,uVar13);
                            if (cVar6 != '\0') {
                              uVar13 = FUN_140071970(local_898,"-o" /*DAT_140ad1a90*/);
                              cVar6 = FUN_140112aa0(&DAT_140bdf6d0,uVar13);
                              if (cVar6 != '\0') {
                                uVar13 = FUN_140071970(local_898,"-w" /*DAT_140ad1b3c*/);
                                cVar6 = FUN_140112aa0(&DAT_140bdf6d0,uVar13);
                                if (cVar6 != '\0') {
                                  uVar13 = FUN_140071970(local_898,"-h" /*DAT_140ad1b48*/);
                                  cVar6 = FUN_140112aa0(&DAT_140bdf6d0,uVar13);
                                  if (cVar6 != '\0') {
                                    uVar13 = FUN_140071970(&local_838,"-w" /*DAT_140ad1b3c*/);
                                    uVar13 = FUN_140137060(extraout_XMM0_Da_02,&local_918,uVar13);
                                    uVar13 = FUN_1400498e0(uVar13);
                                    uVar33 = FUN_140972c50(uVar13);
                                    FUN_140072260(&local_918);
                                    uVar13 = FUN_140071970(&local_8d8,"-h" /*DAT_140ad1b48*/);
                                    uVar13 = FUN_140137060(extraout_XMM0_Da_03,local_898,uVar13);
                                    uVar13 = FUN_1400498e0(uVar13);
                                    uVar9 = FUN_140972c50(uVar13);
                                    FUN_140072260(local_898);
                                    uVar13 = FUN_140071970(&local_8d8,"-d" /*DAT_140ad1b44*/);
                                    uVar13 = FUN_140137060(extraout_XMM0_Da_04,local_898,uVar13);
                                    uVar13 = FUN_1400498e0(uVar13);
                                    iVar8 = FUN_140972c50(uVar13);
                                    FUN_140072260(local_898);
                                    uVar13 = FUN_140071970(&local_8d8,"-f" /*DAT_140ad1b40*/);
                                    uVar13 = FUN_140137060(extraout_XMM0_Da_05,local_898,uVar13);
                                    FUN_14000d3a0(&local_9e8,uVar13);
                                    FUN_140072260(local_898);
                                    uVar13 = FUN_140071970(&local_8d8,"-o" /*DAT_140ad1a90*/);
                                    uVar13 = FUN_140137060(extraout_XMM0_Da_06,local_898,uVar13);
                                    FUN_14000d3a0(&local_a08,uVar13);
                                    FUN_140072260(local_898);
                                    if ((0 < (int)uVar33) && (0 < (int)uVar9)) {
                                      if (iVar8 < 1) {
                                        iVar8 = 4;
                                      }
                                      local_868 = iVar8;
                                      cVar6 = FUN_14000e640(&local_9e8);
                                      if ((cVar6 != '\0') &&
                                         (cVar6 = FUN_14000e640(&local_a08), cVar6 == '\0')) {
                                        cVar6 = FUN_14000e6a0(&local_a08);
                                        if (cVar6 != '\0') {
                                          FUN_14000ea30(&local_a08);
                                        }
                                        local_948 = 0;
                                        local_res10 = 0;
                                        local_958 = 0;
                                        lStack_950 = 0;
                                        iVar30 = 0;
                                        local_res18 = FUN_14000d7a0(&local_9e8);
                                        FUN_1400717f0(local_8f8,&local_res18);
                                        FUN_140137a70(&local_9a8,local_8f8);
                                        FUN_140071e80(local_8f8);
                                        uVar13 = FUN_140017f80(&local_918,&local_9a8);
                                        FUN_140068c60(&local_988,uVar13);
                                        uVar13 = FUN_140017f80(&local_978,&local_9a8);
                                        FUN_140068be0(local_7e8,uVar13);
                                        lVar29 = CONCAT44(uStack_984,local_988);
                                        lVar11 = local_7e8[0];
                                        uVar21 = uVar28;
                                        if (lVar29 != local_7e8[0]) {
                                          do {
                                            FUN_140071c40(local_8f8,lVar29 + 0x20);
                                            cVar6 = FUN_14012cf10(local_8f8);
                                            if (cVar6 != '\0') {
                                              local_918 = (undefined8 *******)0x0;
                                              uVar13 = FUN_14000d7a0(local_8f8);
                                              cVar6 = FUN_140257f20(uVar13,&local_918,&local_res18,
                                                                    &local_res20);
                                              uVar34 = local_res20;
                                              uVar32 = local_res18;
                                              if (cVar6 != '\0') {
                                                uVar5 = (uint)local_res20;
                                                uStack_968 = 0;
                                                uStack_964 = 0;
                                                uVar26 = (uint)local_res18;
                                                local_978 = (undefined8 *)0x0;
                                                uStack_970._0_4_ = 0;
                                                uStack_970._4_4_ = 0;
                                                FUN_140048c80(&local_978,
                                                              (uint)local_res18 * (uint)local_res20
                                                              * 4);
                                                pppppppuVar25 = local_918;
                                                FUN_1409d12d0(local_978,local_918,
                                                              CONCAT44(uStack_970._4_4_,
                                                                       (uint)uStack_970) -
                                                              (longlong)local_978);
                                                thunk_FUN_14098ca30(pppppppuVar25);
                                                if ((int)uVar21 == 0) {
                                                  uVar21 = uVar32 & 0xffffffff;
                                                  local_res10 = uVar5;
                                                }
                                                if (((uint)uVar21 == uVar26) &&
                                                   (local_res10 == uVar5)) {
                                                  uVar28 = (ulonglong)((int)uVar28 + 1);
                                                  if ((uVar33 < uVar26) || (uVar9 < uVar5)) {
                                                    local_a48 = (undefined8 *******)
                                                                CONCAT44(local_a48._4_4_,uVar9);
                                                    FUN_140258b30(&local_978,uVar32 & 0xffffffff,
                                                                  uVar34 & 0xffffffff,uVar33);
                                                    uVar26 = uVar33;
                                                    uVar5 = uVar9;
                                                  }
                                                  uVar9 = uVar5;
                                                  uVar33 = uVar26;
                                                  lVar29 = (longlong)(int)(uVar9 * uVar33 * 4);
                                                  FUN_140048c80(&local_958,
                                                                (lVar29 - local_958) + lStack_950);
                                                  FUN_1409d12d0(lStack_950 - lVar29,local_978,lVar29
                                                               );
                                                }
                                                FUN_14004fe70(&local_978);
                                                lVar11 = local_7e8[0];
                                              }
                                            }
                                            iVar30 = (int)uVar28;
                                            FUN_140071e80(local_8f8);
                                            FUN_1400696a0(&local_988);
                                            lVar29 = CONCAT44(uStack_984,local_988);
                                            iVar8 = local_868;
                                          } while (lVar29 != lVar11);
                                        }
                                        FUN_140068c90(local_7e8);
                                        FUN_140068c90(&local_988);
                                        FUN_140068c90(&local_9a8);
                                        uVar13 = FUN_140071970(&local_8d8,"-c" /*DAT_140ad1b5c*/);
                                        uVar13 = FUN_140137060(extraout_XMM0_Da_07,local_898,uVar13)
                                        ;
                                        uVar13 = FUN_1400498e0(uVar13);
                                        iVar10 = FUN_140972c50(uVar13);
                                        FUN_140072260(local_898);
                                        iVar31 = 0x100;
                                        if (1 < iVar10) {
                                          iVar31 = 0x100;
                                          if (iVar10 < 0x100) {
                                            iVar31 = iVar10;
                                          }
                                          if (iVar31 < 2) {
                                            iVar31 = 2;
                                          }
                                        }
                                        uVar32 = 0;
                                        local_a18 = 0;
                                        local_928 = 0;
                                        local_a28 = (undefined8 *******)0x0;
                                        pppppppuStack_a20 = (undefined8 *******)0x0;
                                        local_938 = (undefined8 *****)0x0;
                                        lStack_930 = 0;
                                        uVar21 = local_878[0];
                                        if (iVar30 < 1) {
LAB_140134603:
                                          bVar35 = true;
                                        }
                                        else {
                                          uVar21 = 1;
                                          uVar13 = FUN_140071970(&local_8d8,"-dithered");
                                          uVar13 = FUN_140137060(extraout_XMM0_Da_08,local_898,
                                                                 uVar13);
                                          local_a38 = FUN_140042480(uVar13,"true" /*DAT_140ac75d0*/);
                                          local_a48 = (undefined8 *******)
                                                      CONCAT44(local_a48._4_4_,iVar30 * uVar9);
                                          local_a40 = iVar31;
                                          cVar6 = FUN_1402624a0(&local_958,&local_a28,&local_938,
                                                                uVar33);
                                          if (cVar6 == '\0') goto LAB_140134603;
                                          bVar35 = false;
                                        }
                                        if ((uVar21 & 1) != 0) {
                                          FUN_140072260(local_898);
                                        }
                                        if (!bVar35) {
                                          local_9c8 = (undefined8 *******)0x0;
                                          uStack_9c0 = 0;
                                          local_9b8 = 0;
                                          FUN_140048c80(&local_9c8,
                                                        (lStack_930 - (longlong)local_938 >> 2) * 3)
                                          ;
                                          uVar21 = uVar32;
                                          if (lStack_930 - (longlong)local_938 >> 2 != 0) {
                                            do {
                                              iVar31 = (int)uVar21;
                                              uVar26 = *(uint *)((longlong)local_938 +
                                                                (longlong)iVar31 * 4);
                                              local_res10._2_1_ = (undefined1)(uVar26 >> 0x10);
                                              *(short *)((longlong)(iVar31 * 3) +
                                                        (longlong)local_9c8) = (short)uVar26;
                                              *(undefined1 *)
                                               ((longlong)(iVar31 * 3) + 2 + (longlong)local_9c8) =
                                                   local_res10._2_1_;
                                              uVar21 = (ulonglong)(iVar31 + 1U);
                                              local_res10 = uVar26;
                                            } while ((ulonglong)(longlong)(int)(iVar31 + 1U) <
                                                     (ulonglong)
                                                     (lStack_930 - (longlong)local_938 >> 2));
                                          }
                                          uStack_8c8 = 2;
                                          local_8aa = 0;
                                          uStack_8bc = (undefined2)
                                                       (lStack_930 - (longlong)local_938 >> 2);
                                          uStack_8ba = 0;
                                          uStack_8b8 = 0;
                                          uStack_8b2 = 0;
                                          uStack_8b0 = 0;
                                          local_8c0 = (undefined2)uVar33;
                                          local_978 = (undefined8 *)0x0;
                                          uStack_970._0_4_ = 0;
                                          uStack_970._4_4_ = 0;
                                          uStack_8be = (undefined2)uVar9;
                                          uStack_968 = 0;
                                          uStack_964 = 0;
                                          uStack_960 = 0;
                                          local_8d8 = local_9c8;
                                          local_8d0 = (undefined4 *)FUN_14000d7a0(&local_a08);
                                          uVar13 = FUN_140139090(&local_8d8);
                                          if (0 < iVar30) {
                                            uVar21 = uVar32;
                                            do {
                                              uStack_964 = 2;
                                              uStack_960 = CONCAT62(uStack_960._2_6_,(short)iVar8);
                                              uStack_970 = (longlong)
                                                           (int)((int)uVar21 * uVar33 * uVar9) +
                                                           (longlong)local_a28;
                                              FUN_140139aa0(uVar13,&local_978);
                                              uVar26 = (int)uVar21 + 1;
                                              uVar21 = (ulonglong)uVar26;
                                            } while ((int)uVar26 < iVar30);
                                          }
                                          FUN_140139ce0(uVar13);
                                          FUN_14004fe70(&local_9c8);
                                          FUN_140067ef0(&local_938);
                                          FUN_14004fe70(&local_a28);
                                          FUN_14004fe70(&local_958);
                                          FUN_140071e80(&local_a08);
                                          pppppppuVar25 = (undefined8 *******)&local_9e8;
                                          goto LAB_14013349e;
                                        }
                                        FUN_140067ef0(&local_938);
                                        FUN_14004fe70(&local_a28);
                                        FUN_14004fe70(&local_958);
                                      }
                                    }
                                    FUN_140071e80(&local_a08);
                                    FUN_140071e80(&local_9e8);
                                  }
                                }
                              }
                            }
LAB_14013464a:
                            uVar34 = 0xffffffff;
                          }
                          else {
                            local_9f8 = 0;
                            uStack_9f0 = 0;
                            local_a08 = (undefined8 *******)0x0;
                            lStack_a00 = 0;
                            FUN_1400718b0(&local_a08,"-i" /*DAT_140ad1a94*/,2);
                            cVar6 = FUN_140112aa0(&DAT_140bdf6d0,&local_a08);
                            if (cVar6 == '\0') {
LAB_1401338c4:
                              uVar34 = 0xffffffff;
                              goto LAB_1401334a6;
                            }
                            local_9f8 = 0;
                            uStack_9f0 = 0;
                            local_a08 = (undefined8 *******)0x0;
                            lStack_a00 = 0;
                            uVar36 = FUN_1400718b0(&local_a08,"-i" /*DAT_140ad1a94*/,2);
                            uVar13 = FUN_140137060(uVar36,&local_9e8,&local_a08);
                            FUN_14000d3a0(local_8f8,uVar13);
                            FUN_140072260(&local_9e8);
                            FUN_140071c40(&local_9e8,local_8f8);
                            FUN_140068de0(&local_988,&local_9e8);
                            FUN_140071e80(&local_9e8);
                            plVar14 = (longlong *)FUN_140017f80(local_7e8,&local_988);
                            local_910 = plVar14[1];
                            pppppppuVar25 = (undefined8 *******)*plVar14;
                            *plVar14 = 0;
                            plVar14[1] = 0;
                            local_918 = pppppppuVar25;
                            uVar13 = FUN_140017f80(local_878,&local_988);
                            FUN_140068be0(&local_9a8,uVar13);
                            pppppppuVar23 = local_9a8;
                            while (pppppppuVar25 != pppppppuVar23) {
                              FUN_140071c40(&local_a28,pppppppuVar25 + 4);
                              cVar6 = FUN_14000e3b0(&local_a28);
                              if (cVar6 != '\0') {
                                FUN_14000dc90(&local_a28,&local_9e8);
                                puVar12 = (undefined8 *)FUN_14000dab0(&local_9e8,&local_a08);
                                plVar14 = puVar12 + 2;
                                if (0xf < (ulonglong)puVar12[3]) {
                                  puVar12 = (undefined8 *)*puVar12;
                                }
                                if (*plVar14 == 4) {
                                  iVar8 = FUN_1409d1dc0(puVar12,".tex" /*DAT_140ab58bc*/);
                                  bVar35 = iVar8 == 0;
                                }
                                else {
                                  bVar35 = false;
                                }
                                FUN_140072260(&local_a08);
                                if (bVar35) {
                                  FUN_14004b800(local_768,1);
                                  uVar13 = FUN_140042940(local_758,"-transcode -i \"");
                                  puVar20 = (undefined8 *)FUN_14000dab0(&local_a28,&local_9c8);
                                  puVar12 = puVar20 + 2;
                                  if (0xf < (ulonglong)puVar20[3]) {
                                    puVar20 = (undefined8 *)*puVar20;
                                  }
                                  uVar13 = FUN_14004c880(uVar13,puVar20,*puVar12);
                                  uVar13 = FUN_140042940(uVar13,"\" -o \"");
                                  puVar20 = (undefined8 *)FUN_14000dab0(&local_a28,&local_a08);
                                  puVar12 = puVar20 + 2;
                                  if (0xf < (ulonglong)puVar20[3]) {
                                    puVar20 = (undefined8 *)*puVar20;
                                  }
                                  uVar13 = FUN_14004c880(uVar13,puVar20,*puVar12);
                                  FUN_140042940(uVar13,"\"" /*DAT_140ab3c1c*/);
                                  FUN_140072260(&local_a08);
                                  FUN_140072260(&local_9c8);
                                  FUN_1402333c0(local_5d8);
                                  local_570 = 1;
                                  puVar12 = (undefined8 *)FUN_14004b680(local_768,&local_a08);
                                  if (0xf < (ulonglong)puVar12[3]) {
                                    puVar12 = (undefined8 *)*puVar12;
                                  }
                                  FUN_1402334d0(local_5d8,"resourcecompiler64.exe",puVar12,0);
                                  FUN_140072260(&local_a08);
                                  cVar6 = FUN_140234470(local_5d8,300000,&local_res10);
                                  if (cVar6 == '\0') {
                                    FUN_140233440(local_5d8);
                                    FUN_14000c5e0(local_768);
                                    FUN_140071e80(&local_9e8);
                                    FUN_140071e80(&local_a28);
                                    break;
                                  }
                                  FUN_140233440(local_5d8);
                                  FUN_14000c5e0(local_768);
                                }
                                FUN_140071e80(&local_9e8);
                              }
                              FUN_140071e80(&local_a28);
                              FUN_140068710(&local_918);
                              pppppppuVar25 = local_918;
                            }
                            FUN_140068c90(&local_9a8);
                            FUN_140068c90(&local_918);
                            FUN_140068c90(&local_988);
                            pppppppuVar25 = local_8f8;
LAB_14013349e:
                            FUN_140071e80(pppppppuVar25);
                            uVar34 = uVar32;
                          }
                        }
                        else {
                          local_9f8 = 2;
                          lStack_a00 = 0;
                          local_a08 = (undefined8 *******)0x692d;
                          uStack_9f0 = 0xf;
                          cVar6 = FUN_140112aa0(&DAT_140bdf6d0,&local_a08);
                          if (cVar6 == '\0') {
                            uVar34 = 0xffffffff;
                          }
                          else {
                            local_9f8 = 0;
                            uStack_9f0 = 0;
                            local_a08 = (undefined8 *******)0x0;
                            lStack_a00 = 0;
                            FUN_1400718b0(&local_a08,"-o" /*DAT_140ad1a90*/,2);
                            cVar6 = FUN_140112aa0(&DAT_140bdf6d0,&local_a08);
                            if (cVar6 == '\0') {
                              uVar34 = 0xffffffff;
                            }
                            else {
                              local_9f8 = 0;
                              uStack_9f0 = 0;
                              local_a08 = (undefined8 *******)0x0;
                              lStack_a00 = 0;
                              uVar36 = FUN_1400718b0(&local_a08,"-i" /*DAT_140ad1a94*/,2);
                              uVar13 = FUN_140137060(uVar36,&local_9e8,&local_a08);
                              FUN_14000d3a0(&local_9c8,uVar13);
                              FUN_140072260(&local_9e8);
                              local_9f8 = 0;
                              uStack_9f0 = 0;
                              local_a08 = (undefined8 *******)0x0;
                              lStack_a00 = 0;
                              uVar36 = FUN_1400718b0(&local_a08,"-o" /*DAT_140ad1a90*/,2);
                              uVar13 = FUN_140137060(uVar36,&local_9e8,&local_a08);
                              FUN_14000d3a0(&local_a28,uVar13);
                              FUN_140072260(&local_9e8);
                              cVar6 = FUN_14000e6a0(&local_9c8);
                              if (cVar6 != '\0') {
                                cVar6 = FUN_14000e640(&local_a28);
                                if (cVar6 == '\0') {
                                  FUN_14000eb20(&local_a28);
                                }
                                cVar6 = FUN_14000e640(&local_a28);
                                if (cVar6 != '\0') {
                                  FUN_140471810(local_768);
                                  uVar13 = FUN_14000dab0(&local_9c8,&local_9e8);
                                  uVar13 = FUN_1400498e0(uVar13);
                                  iVar8 = FUN_140474430(local_768,uVar13);
                                  FUN_140072260(&local_9e8);
                                  if (iVar8 == 0) {
                                    uVar13 = FUN_14000dab0(&local_a28,&local_9e8);
                                    uVar13 = FUN_1400498e0(uVar13);
                                    FUN_140474d00(local_768,uVar13);
                                    FUN_140072260(&local_9e8);
                                    FUN_14002a710(local_768);
                                    FUN_140071e80(&local_a28);
                                    pppppppuVar25 = &local_9c8;
                                    uVar32 = uVar28;
                                    goto LAB_14013349e;
                                  }
                                  FUN_14002a710(local_768);
                                }
                              }
                              FUN_140071e80(&local_a28);
                              FUN_140071e80(&local_9c8);
                              uVar34 = 0xffffffff;
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
LAB_1401334a6:
  if (0xf < local_848) {
    uVar21 = local_848 + 1;
    pppppppuVar25 = local_860[0];
    if (0xfff < uVar21) {
      pppppppuVar25 = (undefined8 *******)local_860[0][-1];
      if (0x1f < (ulonglong)((longlong)local_860[0] + (-8 - (longlong)pppppppuVar25))) {
LAB_1401347d4:
        pcVar2 = (code *)swi(0x29);
        (*pcVar2)(5);
        puVar27 = auStack_a60;
LAB_1401347db:
        *(undefined8 *)(puVar27 + -8) = 0x1401347e5;
        FUN_14006fdc0(0x16);
        pcVar2 = (code *)swi(3);
        uVar21 = (*pcVar2)();
        return uVar21;
      }
      uVar21 = local_848 + 0x28;
    }
    thunk_FUN_14098ca30(pppppppuVar25,uVar21);
  }
  return uVar34;
}



