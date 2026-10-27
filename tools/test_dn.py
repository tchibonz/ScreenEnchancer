import base64, sys, numpy as np, cv2
from skimage import data
from playwright.sync_api import sync_playwright
SH='/home/claude/ScreenEnhancer/app/src/main/assets/shaders/'
rd=lambda n: open(SH+n).read()
JS=r'''
async (cfg) => {
  const c=document.createElement('canvas'); document.body.appendChild(c); const gl=c.getContext('webgl2');
  function sh(t,s){const x=gl.createShader(t);gl.shaderSource(x,s);gl.compileShader(x);if(!gl.getShaderParameter(x,gl.COMPILE_STATUS))throw new Error(gl.getShaderInfoLog(x));return x;}
  const p=gl.createProgram();gl.attachShader(p,sh(gl.VERTEX_SHADER,cfg.vert));gl.attachShader(p,sh(gl.FRAGMENT_SHADER,cfg.fs));
  gl.bindAttribLocation(p,0,'aPos');gl.bindAttribLocation(p,1,'aUV');gl.linkProgram(p);
  if(!gl.getProgramParameter(p,gl.LINK_STATUS))throw new Error(gl.getProgramInfoLog(p));
  const vb=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,vb);
  gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([-1,-1,0,0,1,-1,1,0,-1,1,0,1,1,1,1,1]),gl.STATIC_DRAW);
  gl.enableVertexAttribArray(0);gl.vertexAttribPointer(0,2,gl.FLOAT,false,16,0);gl.enableVertexAttribArray(1);gl.vertexAttribPointer(1,2,gl.FLOAT,false,16,8);
  const fbo=gl.createFramebuffer(); const out=[];
  for(const im of cfg.images){
    const W=im.w,H=im.h,bin=atob(im.rgba),b=new Uint8Array(bin.length);for(let i=0;i<b.length;i++)b[i]=bin.charCodeAt(i);
    function tex(){const t=gl.createTexture();gl.bindTexture(gl.TEXTURE_2D,t);gl.texStorage2D(gl.TEXTURE_2D,1,gl.RGBA8,W,H);
      for(const [k,v] of [[gl.TEXTURE_MIN_FILTER,gl.LINEAR],[gl.TEXTURE_MAG_FILTER,gl.LINEAR],[gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE],[gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE]])gl.texParameteri(gl.TEXTURE_2D,k,v);return t;}
    const a=tex();gl.texSubImage2D(gl.TEXTURE_2D,0,0,0,W,H,gl.RGBA,gl.UNSIGNED_BYTE,b);const o=tex();
    const r={};
    for(const s of cfg.strengths){
      gl.bindFramebuffer(gl.FRAMEBUFFER,fbo);gl.framebufferTexture2D(gl.FRAMEBUFFER,gl.COLOR_ATTACHMENT0,gl.TEXTURE_2D,o,0);
      gl.viewport(0,0,W,H);gl.useProgram(p);
      gl.uniformMatrix4fv(gl.getUniformLocation(p,'uST'),false,new Float32Array([1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1]));
      gl.activeTexture(gl.TEXTURE0);gl.bindTexture(gl.TEXTURE_2D,a);gl.uniform1i(gl.getUniformLocation(p,'uVideo'),0);
      gl.uniform2f(gl.getUniformLocation(p,'uSize'),W,H);gl.uniform1f(gl.getUniformLocation(p,'uDn'),s);
      gl.drawArrays(gl.TRIANGLE_STRIP,0,4);const e=gl.getError();if(e)throw new Error('gl '+e);
      const px=new Uint8Array(W*H*4);gl.readPixels(0,0,W,H,gl.RGBA,gl.UNSIGNED_BYTE,px);
      let s2='';for(let i=0;i<px.length;i+=8192)s2+=String.fromCharCode.apply(null,px.subarray(i,i+8192));r[s]=btoa(s2);
    }
    out.push(r);
  }
  return out;
}'''
def psnr(a,b):
    a=a.astype(np.float32);b=b.astype(np.float32);m=np.mean((a[4:-4,4:-4]-b[4:-4,4:-4])**2);return 10*np.log10(255**2/m)
imgs=[data.astronaut(),data.coffee(),data.chelsea(),data.rocket()]
clean=[];bad=[]
rng=np.random.default_rng(1)
for im in imgs:
    h,w=im.shape[:2];im=im[:min(h,240)//2*2,:min(w,320)//2*2]
    c=np.ascontiguousarray(im);clean.append(c)
    ok,buf=cv2.imencode('.jpg',cv2.cvtColor(c,cv2.COLOR_RGB2BGR),[cv2.IMWRITE_JPEG_QUALITY,22])
    d=cv2.cvtColor(cv2.imdecode(buf,1),cv2.COLOR_BGR2RGB).astype(np.float32)+rng.normal(0,3,c.shape)
    bad.append(np.clip(d,0,255).astype(np.uint8))
S=[0.0,0.3,0.6,1.0]
cfg={'vert':rd('vert.vert'),'fs':rd('dn.frag').replace('//__VIDEO__','uniform sampler2D uVideo;'),'strengths':S,
 'images':[{'w':b.shape[1],'h':b.shape[0],'rgba':base64.b64encode(np.dstack([b,np.full(b.shape[:2],255,np.uint8)]).tobytes()).decode()} for b in bad]}
with sync_playwright() as p:
    br=p.chromium.launch(args=['--use-angle=swiftshader','--use-gl=angle','--enable-unsafe-swiftshader','--ignore-gpu-blocklist']);pg=br.new_page()
    res=pg.evaluate(JS,cfg);br.close()
print('input (jpeg q22 + noise): PSNR %.2f'%np.mean([psnr(c,b) for c,b in zip(clean,bad)]))
for s in S:
    outs=[np.frombuffer(base64.b64decode(r['%g'%s]),np.uint8).reshape(c.shape[0],c.shape[1],4)[:,:,:3] for r,c in zip(res,clean)]
    print('dn %.1f: PSNR %.2f'%(s,np.mean([psnr(c,o) for c,o in zip(clean,outs)])))
