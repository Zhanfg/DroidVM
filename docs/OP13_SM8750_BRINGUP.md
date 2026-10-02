# OnePlus 13 / SM8750 Gunyah bring-up

This branch isolates PJZ110 / SM8750 bring-up work from upstream `master`.

## Confirmed host facts

- Device: PJZ110 / OP5D0DL1
- SoC: SM8750
- Android: 16
- Host kernel: 6.6.x
- `/dev/gunyah`: present
- `/dev/qgunyah`: present
- `/dev/kvm`: absent
- AOSP Terminal's non-protected AVF path is not a usable baseline on this firmware.
- Qualcomm QVirt userspace exists, but the firmware's `vm-bootsys` / `vm-persist` guest payload partitions are unprovisioned on this SKU.

## Why use DroidVM first

DroidVM already has the pieces needed for the safer baseline:

- SM8750 is listed as Gunyah-capable.
- New VM defaults use 1 vCPU / 512 MiB.
- Gunyah maps to crosvm's Gunyah backend.
- New VMs default to `PSEUDO_UNPROTECTED`, avoiding the lent-memory DMA restriction that requires a guest kernel with `CONFIG_DMA_RESTRICTED_POOL`.
- The app ships a known-good guest `vmlinuz` + `initramfs`; upstream issue #12 reports OnePlus 13 booting after switching to the APK-provided pair.
- MTHP and huge-page reserve handling are already present, but the reserve kernel module must NOT be added during the first baseline run.

## CI baseline

GitHub Actions has been enabled on this fork. This branch is expected to build an unmodified DroidVM baseline before any PJZ110-specific runtime changes are introduced.

## First baseline test

Do not load `gh_hugepage_reserve` yet.

Create a Linux VM using the built-in kernel/initramfs and keep the first run deliberately small:

- backend: crosvm
- hypervisor: Gunyah
- protected mode: pseudo-unprotected
- RAM: 512 MiB
- vCPU: 1
- GPU: off
- USB: off for the first run
- sound: off for the first run
- network: off for the first run
- shared folders: none
- disk: one Linux root disk
- MTHP: leave the device default unless diagnostics say otherwise

The first acceptance criterion is only: guest reaches userspace and the serial/app console remains responsive for several minutes.

## Stop conditions

Stop testing and collect logs instead of retrying if any of these occurs:

- host UI freezes or input stalls;
- Gunyah RM reports `NORESOURCE` / `-ENODEV`;
- crosvm reports host page fault / OOM;
- kernel reports `qcom_scm` memory assignment failures;
- watchdog / RCU / soft-lockup messages appear.

Do not retry the raw `--protected-vm-without-firmware` command used during the earlier host-freeze experiment.

## Next implementation steps

1. Build this branch unchanged to establish a DroidVM baseline on PJZ110.
2. Capture DroidVM/crosvm/kernel logs from one minimal start.
3. Only after a successful baseline, add PJZ110-specific safety guards and a reproducible preset.
4. Only add `gh_hugepage_reserve` if logs prove a huge-page shortage; never use it as a blind first fix.
5. Once the stable Gunyah command line is known, port that command construction back toward the Android 16 Terminal integration.


## Validated baseline (PJZ110)

Validated on 2026-10-02 on PJZ110 / SM8750 with Android 16 and host kernel 6.6.147.

Working minimal configuration:

- backend: crosvm
- hypervisor: Gunyah
- protected mode: pseudo-unprotected
- vCPU: 1
- RAM: 512 MiB
- hugepages: enabled
- prepare lend mTHP: chunked (<=256 MiB)
- PMU: off
- RNG: off
- SMT: off
- boot protocol: Linux
- kernel source: manual
- kernel: DroidVM built-in vmlinuz
- initrd: DroidVM built-in initramfs.img
- cmdline: root=/dev/vda2
- one writable virtio-blk disk
- network: disabled
- virtio-gpu: disabled
- simplefb: disabled
- VPU: disabled
- only Serial 1 attached to the app console; remaining serial ports sink output

Observed result:

- guest booted successfully to Alpine userspace;
- root login on the app serial console succeeded;
- host remained responsive during the initial boot;
- no hugepage reserve kernel module was required for this baseline.

This proves the device can run a minimal generic Linux guest through DroidVM's crosvm + Gunyah pseudo-unprotected path. It does **not** yet prove stability with networking, GPU/display, USB, shared folders, long-running load, or raw protected-without-firmware mode.

### Next staged tests

Enable one subsystem at a time and keep the last known-good config available:

1. network only;
2. RNG;
3. SimpleFB/display;
4. USB/sound only if needed;
5. PMU/SMT last;
6. hugepage reserve module only if logs show an actual memory-pinning/fragmentation failure.


## Validated network stage (PJZ110)

Validated on 2026-10-02 with the minimal baseline plus one DroidVM bridge NIC.

Observed in the Alpine guest:

- eth0 present and UP;
- DHCP IPv4 lease acquired: 192.168.82.128/24;
- default route via 192.168.82.1;
- ICMP to 1.1.1.1 succeeded with 0% packet loss;
- IPv6 link-local/global addresses were also assigned;
- guest remained usable and root shell stayed responsive.

Guest dmesg still prints:

- `gunyah: RM rejected message 00000005. Error: -1`
- `gunyah: Error in returning log: -95`

These messages did not prevent boot, virtio-blk operation, DHCP, routing, or external network reachability in this stage, so they are recorded as non-blocking observations rather than treated as the current failure cause.

Next staged test: enable RNG only while keeping SimpleFB/GPU/USB/sound/PMU/SMT disabled.


## Validated SimpleFB stage (PJZ110)

Validated on 2026-10-02 with the previously working Gunyah + network baseline and SimpleFB enabled.

Observed result:

- DroidVM display connected successfully at 1280x720;
- Alpine console rendered correctly in the app display;
- serial console remained usable;
- guest reached Alpine userspace and root shell;
- network remained enabled from the previous stage;
- host Android remained responsive.

Working display-stage configuration:

- virtio-gpu: disabled
- SimpleFB: enabled
- display resolution: 1280x720
- refresh rate: 60 Hz
- native display/export path: app display
- VPU: disabled

This confirms a usable framebuffer/display path on PJZ110 over DroidVM's crosvm + Gunyah pseudo-unprotected configuration. It does not yet validate virtio-gpu/VirGL/GfxStream.

Next staged tests:
1. RNG only;
2. then virtio-gpu with SimpleFB retained as fallback;
3. only after virtio-gpu is stable, test accelerated renderers one at a time.


## Validated RNG stage (PJZ110)

Validated on 2026-10-02 with the working Gunyah + network + SimpleFB baseline and virtio RNG enabled.

Observed result:

- guest booted successfully;
- Alpine userspace and root shell remained usable;
- network remained functional;
- SimpleFB display remained functional;
- host Android remained responsive.

This validates virtio RNG on the PJZ110 Gunyah pseudo-unprotected baseline.

Next staged test: enable basic virtio-gpu while keeping SimpleFB enabled as a fallback, with accelerated renderers still disabled.


## Validated virtio-gpu 2D stage (PJZ110)

Validated on 2026-10-02 with the working Gunyah + network + RNG + SimpleFB baseline and virtio-gpu enabled using the software 2D renderer.

Observed result:

- gpu-0 display connected successfully at 1280x720;
- Alpine console rendered on gpu-0;
- guest userspace remained usable;
- SimpleFB fallback remained available;
- network and RNG remained functional;
- host Android remained responsive.

This validates the virtio-gpu device and non-accelerated scanout path on PJZ110. Hardware acceleration is not yet validated.

Next staged test: virglrenderer in Native Context mode with DRM2KGSL, retaining SimpleFB as fallback and leaving PMU/SMT/VPU/USB/sound disabled.


## Validated virglrenderer native DRM2KGSL stage (PJZ110)

Validated on 2026-10-02 with the previously working Gunyah + network + RNG + SimpleFB + virtio-gpu baseline.

Configuration:

- virtio-gpu: enabled
- renderer backend: virglrenderer
- GPU mode: Native Context
- provider: DRM2KGSL
- SimpleFB: retained as fallback
- PMU/SMT/VPU/USB/sound: disabled
- network/RNG: enabled

Observed result:

- guest booted normally;
- gpu-0 display remained usable at the same 1280x720 baseline;
- Alpine userspace remained usable;
- host Android remained responsive;
- no user-visible regression versus the 2D stage was observed.

This validates the Native Context + DRM2KGSL path functionally on PJZ110. Performance/acceleration quality is not yet benchmarked.

Next staged test: virglrenderer + Vulkan (Venus) + Turnip, keeping SimpleFB enabled as fallback and leaving PMU/SMT/VPU/USB/sound disabled.
