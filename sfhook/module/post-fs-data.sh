#!/system/bin/sh
# SurfaceFlinger loads compiled programs from its disk cache (glProgramBinary) and then never calls
# glShaderSource, so the probe would see nothing. Drop the cache before it starts; it is rebuilt.
rm -f /data/misc/surfaceflinger/skia_shaders /data/misc/surfaceflinger/egl_shaders
