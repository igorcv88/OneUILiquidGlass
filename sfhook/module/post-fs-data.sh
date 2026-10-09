#!/system/bin/sh
# SurfaceFlinger loads compiled programs from its disk cache (glProgramBinary) and then never calls
# glShaderSource, so the probe would see nothing. Drop the cache before it starts; it is rebuilt.
# The previous boot's shader dump is dropped too.
rm -f /data/misc/surfaceflinger/skia_shaders /data/misc/surfaceflinger/egl_shaders /data/misc/surfaceflinger/oulg_shaders.txt
