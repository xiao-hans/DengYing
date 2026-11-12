const fs=require('fs'),zlib=require('zlib');
function dec(p){const b=fs.readFileSync(p);let o=8,w,h,bd,ct,idat=[];
while(o<b.length){const len=b.readUInt32BE(o);const t=b.toString('ascii',o+4,o+8);
if(t==='IHDR'){w=b.readUInt32BE(o+8);h=b.readUInt32BE(o+12);bd=b[o+16];ct=b[o+17];}
else if(t==='IDAT'){idat.push(b.subarray(o+8,o+8+len));}
else if(t==='IEND')break;o+=12+len;}
const raw=zlib.inflateSync(Buffer.concat(idat));
const ch={0:1,2:3,3:1,4:2,6:4}[ct];const bpp=ch*(bd/8);const stride=w*bpp;
const out=Buffer.alloc(h*stride);let pos=0;
for(let y=0;y<h;y++){const f=raw[pos++];const line=raw.subarray(pos,pos+stride);pos+=stride;
const cur=out.subarray(y*stride,(y+1)*stride);const prev=y>0?out.subarray((y-1)*stride,y*stride):Buffer.alloc(stride);
for(let x=0;x<stride;x++){const a=x>=bpp?cur[x-bpp]:0,bb=prev[x],c=x>=bpp?prev[x-bpp]:0;let v=line[x];
if(f===1)v+=a;else if(f===2)v+=bb;else if(f===3)v+=(a+bb)>>1;else if(f===4){const pp=a+bb-c;const pa=Math.abs(pp-a),pb=Math.abs(pp-bb),pc=Math.abs(pp-c);v+=(pa<=pb&&pa<=pc)?a:(pb<=pc?bb:c);}
cur[x]=v&255;}}
return{w,h,bpp,stride,data:out};}
const [,,src,dst,x0,y0,x1,y1,scale]=process.argv;
const im=dec(src);const X0=+x0,Y0=+y0,X1=+x1,Y1=+y1,S=+(scale||1);
const cw=(X1-X0)*S,chh=(Y1-Y0)*S;
const rows=[];
for(let y=0;y<chh;y++){const row=Buffer.alloc(1+cw*3);row[0]=0;
for(let x=0;x<cw;x++){const sx=X0+Math.floor(x/S),sy=Y0+Math.floor(y/S);
const i=sy*im.stride+sx*im.bpp;row[1+x*3]=im.data[i];row[2+x*3]=im.data[i+1];row[3+x*3]=im.data[i+2];}
rows.push(row);}
const crcT=[];for(let n=0;n<256;n++){let c=n;for(let k=0;k<8;k++)c=c&1?0xEDB88320^(c>>>1):c>>>1;crcT[n]=c>>>0;}
function crc(b){let c=0xFFFFFFFF;for(const v of b)c=crcT[(c^v)&255]^(c>>>8);return (c^0xFFFFFFFF)>>>0;}
function chunk(t,d){const l=Buffer.alloc(4);l.writeUInt32BE(d.length);const td=Buffer.concat([Buffer.from(t,'ascii'),d]);const c=Buffer.alloc(4);c.writeUInt32BE(crc(td));return Buffer.concat([l,td,c]);}
const ih=Buffer.alloc(13);ih.writeUInt32BE(cw,0);ih.writeUInt32BE(chh,4);ih[8]=8;ih[9]=2;
fs.writeFileSync(dst,Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),chunk('IHDR',ih),chunk('IDAT',zlib.deflateSync(Buffer.concat(rows))),chunk('IEND',Buffer.alloc(0))]));
console.log('wrote',dst,cw+'x'+chh);
