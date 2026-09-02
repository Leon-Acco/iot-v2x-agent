
(function(){
  var visual=document.getElementById('heroVisual'),img=document.getElementById('heroImage'),cv=document.getElementById('glitter'),cx=cv.getContext('2d');
  var dpr=1,w=0,h=0,tx=0,ty=0,rx=0,ry=0,cxv=0,cyv=0,crx=0,cry=0,tmx=0,tmy=0,smx=0,smy=0,mouse=false,pts=[];
  function resizeFX(){dpr=Math.min(devicePixelRatio||1,2);w=visual.clientWidth;h=visual.clientHeight;cv.width=w*dpr;cv.height=h*dpr;cv.style.width=w+'px';cv.style.height=h+'px';buildPoints();}
  function buildPoints(){
    if(!img.complete||!img.naturalWidth)return;
    var ow=360,oh=Math.round(ow*img.naturalHeight/img.naturalWidth),o=document.createElement('canvas');o.width=ow;o.height=oh;
    var oc=o.getContext('2d');oc.drawImage(img,0,0,ow,oh);var d=oc.getImageData(0,0,ow,oh).data;pts=[];
    for(var y=0;y<oh;y+=2)for(var x=0;x<ow;x+=2){var q=(y*ow+x)*4,r=d[q],g=d[q+1],b=d[q+2],lum=(r+g+b)/3;
      if(x<ow*.73&&y>oh*.21&&y<oh*.82&&lum<212&&g>=r*.78&&Math.random()>.34)pts.push({x:x/ow,y:y/oh,a:.18+Math.random()*.50,s:.30+Math.random()*1.15,p:Math.random()*6.28,z:Math.random()});
    }
  }
  img.addEventListener('load',buildPoints);addEventListener('resize',resizeFX);resizeFX();
  function track(e){mouse=true;var nx=e.clientX/innerWidth-.5,ny=e.clientY/innerHeight-.5;tmx=nx;tmy=ny;ry=nx*22;rx=-ny*12;tx=nx*48;ty=ny*24;}
  addEventListener('pointermove',track,{passive:true});
  document.addEventListener('mousemove',track,{passive:true});
  addEventListener('touchmove',function(e){if(e.touches&&e.touches[0])track(e.touches[0]);},{passive:true});
  addEventListener('pointerleave',function(){mouse=false;});
  var start=performance.now();
  function draw(now){
    var t=(now-start)/1000;if(!mouse){ry=Math.sin(t*.28)*3.2;rx=Math.cos(t*.22)*1.4;tx=0;ty=0;tmx=0;tmy=0;}
    cry+=(ry-cry)*.055;crx+=(rx-crx)*.055;cxv+=(tx-cxv)*.055;cyv+=(ty-cyv)*.055;
    smx+=(tmx-smx)*.075;smy+=(tmy-smy)*.075;
    visual.style.transform='perspective(1050px) translate3d('+cxv+'px,'+cyv+'px,0) rotateX('+crx+'deg) rotateY('+cry+'deg) scale(1.025)';
    cx.setTransform(dpr,0,0,dpr,0,0);cx.clearRect(0,0,w,h);
    var ir=img.naturalWidth/img.naturalHeight,vr=w/h,iw=w,ih=h,ox=0,oy=0;
    if(vr>ir){iw=h*ir;ox=(w-iw)/2}else{ih=w/ir;oy=(h-ih)/2}
    for(var i=0;i<pts.length;i++){var p=pts[i],tw=.35+.65*Math.max(0,Math.sin(t*2.1+p.p));if(tw<.58)continue;
      var x=ox+p.x*iw-smx*p.z*78,y=oy+p.y*ih-smy*p.z*42,rr=p.s*(1+tw*.65);cx.globalAlpha=p.a*tw;cx.fillStyle=i%7===0?'#f1fff9':'#82e8ca';cx.beginPath();cx.arc(x,y,rr,0,6.283);cx.fill();}
    cx.globalAlpha=1;requestAnimationFrame(draw);
  }
  requestAnimationFrame(draw);
})();

