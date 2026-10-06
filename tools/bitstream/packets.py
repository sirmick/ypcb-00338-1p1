#!/usr/bin/env python3
"""Dump the configuration packets of a 7-series .bit/.bin, collapsing long FAR/FDRI/MFWR runs.

  packets.py design.bit

Use it to compare two bitstreams header-to-trailer (COR0, CTL0/CTL1, CRC, START...)."""
import struct,sys
regs={0:"CRC",1:"FAR",2:"FDRI",4:"CMD",5:"CTL0",6:"MASK",7:"STAT",8:"LOUT",9:"COR0",0xa:"MFWR",0xb:"CBC",0xc:"IDCODE",0xd:"AXSS",0xe:"COR1",0x10:"WBSTAR",0x11:"TIMER",0x13:"RBCRC_SW",0x16:"BOOTSTS",0x18:"CTL1",0x1f:"BSPI"}
d=open(sys.argv[1],"rb").read(); s=d.find(bytes.fromhex("aa995566"))
w=struct.unpack(">%dI"%((len(d)-s)//4), d[s:s+(len(d)-s)//4*4])
i=1; last=None; out=[]
while i<len(w):
    x=w[i]; t=x>>29
    if t==1:
        op=(x>>27)&3; r=(x>>13)&0x1f; n=x&0x7ff; last=r
        if op==2 and n and r!=2 and r!=0xa: out.append("W %s %s"%(regs.get(r,hex(r))," ".join("%08x"%v for v in w[i+1:i+1+min(n,2)])))
        elif op==2 and r in (2,0xa): out.append("W %s [%d]"%(regs[r],n))
        i+=1+n
    elif t==2:
        n=x&0x7ffffff; out.append("W2 %s [%d]"%(regs.get(last,hex(last)),n)); i+=1+n
    else: i+=1
# collapse long FAR/FDRI/MFWR runs
res=[];prev=None;cnt=0
for o in out:
    k=o.split()[1]
    if k in ("FAR","FDRI","MFWR") and prev in ("FAR","FDRI","MFWR"): cnt+=1; continue
    if cnt: res.append("   ... %d more FAR/FDRI/MFWR"%cnt); cnt=0
    res.append(o); prev=k
if cnt: res.append("   ... %d more FAR/FDRI/MFWR"%cnt)
print("\n".join(res))
