#!/usr/bin/env python3
"""Clear heads-up body refraction visibility under the body kernels (docs/clear-glass/HU_DEPTH.md).

Twin of refract.h's Clear body term on a 800 x 382 card (hurefract 1.09), sampled through the
hu_kernel_report twin of oulg_body_hu (kernels 0 and 1).
s(q) = -min(hs) * u * (l0 + l2 * u^2), u = q / hs; the body kernel is centred on q + s(q) (taps unchanged).
Visibility = RMS(O(field) - O(no field)) over the body interior / RMS of the backdrop's 6-120 px structure.
Motion cue: the same for a 6 px scroll, measured as the change of the apparent displacement map.
Usage: python3 -I hu_depth_report.py   (numpy, Pillow; ~35 min on 4 cores; not a CI gate)
"""
import importlib.util, os, sys, numpy as np
spec=importlib.util.spec_from_file_location('hk', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'hu_kernel_report.py')); hk=importlib.util.module_from_spec(spec); spec.loader.exec_module(hk)
H,W,W709=hk.H,hk.W,hk.W709
GOLD=hk.GOLD
def body_shift(tex, R, kernel, sx, sy, taps=48, adapt=1.0):
    y, x = np.mgrid[0:H, 0:W].astype(np.float64) + 0.5
    f = np.mod(x * 0.06711056 + (H - y) * 0.00583715, 1.0); a0 = 2*np.pi*np.mod(52.9829189*f, 1.0)
    cx, cy = x + sx, y + sy
    dx, dy = np.cos(a0), np.sin(a0); rot=lambda dx,dy:(GOLD[0,0]*dx+GOLD[0,1]*dy, GOLD[1,0]*dx+GOLD[1,1]*dy)
    acc=np.zeros((H,W,3)); ws=0.; accb=np.zeros((H,W,3)); wb=0.; m2=np.zeros((H,W))
    wide, lo, hi = (2.5,.06,.22) if kernel==0 else (2.0,)+{1:(.035,.14)}[kernel]
    for i in range(taps):
        rr=np.sqrt((i+.5)/taps); t=hk.bilinear(tex, cx+dx*rr*R, cy+dy*rr*R); w=np.exp(-2*rr*rr); acc+=w*t; ws+=w
        if kernel: v=np.exp(-2*rr*rr/wide**2); yv=t@W709; accb+=v*t; wb+=v; m2+=v*yv*yv
        dx,dy=rot(dx,dy)
    if kernel==0: dx,dy=np.cos(a0),np.sin(a0)
    for j in range(taps):
        if kernel==0: rb=np.sqrt((j+.5)/taps); v=np.exp(-2*rb*rb)
        else: q2=1/wide**2; rb=np.sqrt(q2+(1-q2)*(j+.5)/taps); v=(wide*wide-1)*np.exp(-2*rb*rb)
        t=hk.bilinear(tex, cx+dx*rb*R*wide, cy+dy*rb*R*wide); yv=t@W709; accb+=v*t; wb+=v; m2+=v*yv*yv
        dx,dy=rot(dx,dy)
    a=acc/ws; b=accb/wb; sd=np.sqrt(np.maximum(m2/wb-(b@W709)**2,0))
    return a+(b-a)*(adapt*hk.smoothstep(lo,hi,sd))[...,None]
hs=np.array([400.,191.]); y,x=np.mgrid[0:H,0:W]+0.5; qx=x-W/2; qy=y-H/2; ux=qx/hs[0]; uy=qy/hs[1]
inside=(np.abs(qx)<hs[0]-80)&(np.abs(qy)<hs[1]-80)
def field(l0,l2,refract=1.09): m=min(hs); return (-m*ux*(l0+l2*ux*ux)*refract, -m*uy*(l0+l2*uy*uy)*refract)
S=hk.scenes()
def struct(B): Y=B@W709; return np.sqrt(np.mean((hk.box(Y,6)-hk.box(Y,60))[inside]**2))
print('kernel R    l0    l2   | photo vis  text vis | motion cue photo')
for k,R in ((0,36),(1,36),(1,41.4)):
    for l0,l2 in ((0,.02),(0,.04),(.02,0),(.02,.02)):
        out=[]
        for sc in ('photo','white_on_blue'):
            B=S[sc]; tex=hk.make_tex(B); sx,sy=field(l0,l2)
            on=body_shift(tex,R,k,sx,sy)@W709; off=body_shift(tex,R,k,0*sx,0*sy)@W709
            out.append(np.sqrt(np.mean((on-off)[inside]**2))/struct(B))
        B2=np.roll(S['photo'],6,axis=1); tex2=hk.make_tex(B2); sx,sy=field(l0,l2)
        on2=body_shift(tex2,R,k,sx,sy)@W709; off2=body_shift(tex2,R,k,0*sx,0*sy)@W709
        tex=hk.make_tex(S['photo']); on=body_shift(tex,R,k,sx,sy)@W709; off=body_shift(tex,R,k,0*sx,0*sy)@W709
        mc=np.sqrt(np.mean(((on2-off2)-(on-off))[inside]**2))/struct(S['photo'])
        print(f'K{k} {R:5.1f} {l0:5.2f} {l2:5.2f} | {out[0]*100:8.2f}% {out[1]*100:8.2f}% | {mc*100:8.2f}%', flush=True)
