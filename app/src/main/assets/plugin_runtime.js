(function(PLG){
var B=window["%BRIDGE%"];
var T="%TOKEN%";
var META=%META%;
var CSS=%CSS%;
var KEY="%KEY%";
var isTop=false;
try{isTop=window.top===window;}catch(e){}
try{
var rs=window.__coaraPlgReady;
if(!rs){
rs={};
Object.defineProperty(window,"__coaraPlgReady",{value:rs,enumerable:false,configurable:true});
}
rs[KEY]=1;
}catch(e){}
function inv(op,a,b,c){
try{
if(B){
return B.invoke(T,META.id,op,a==null?"":String(a),b==null?"":String(b),c==null?"":String(c));
}
}catch(e){}
return null;
}
function rx(list,u){
for(var i=0;i<list.length;i++){
try{if(new RegExp(list[i]).test(u))return true;}catch(e){}
}
return false;
}
var href="";
try{href=String(location.href);}catch(e){}
if(!META.allFrames&&!isTop)return"0";
if(META.excludes.length&&rx(META.excludes,href))return"0";
if(!rx(META.matches,href))return"0";
var reports=0;
function rep(kind,message,stack){
if(reports>=5)return;
reports++;
var o={};
try{o.url=String(location.href);}catch(e){o.url="";}
o.stack=stack==null?"":String(stack).substring(0,3000);
inv("report",kind,message==null?"":String(message).substring(0,1000),JSON.stringify(o));
}
function fmt(args){
var out=[];
for(var i=0;i<args.length;i++){
var v=args[i];
if(typeof v==="string"){out.push(v);}
else{
try{
var s=JSON.stringify(v);
out.push(s===undefined?String(v):s);
}catch(e){out.push(String(v));}
}
}
return out.join(" ");
}
function safe(f){
try{f(api);}catch(e){rep("runtime",e&&e.message?e.message:String(e),e&&e.stack);}
}
function addStyle(css){
var text=String(css);
var handle={};
var sheet=null;
var el=null;
try{
if(typeof CSSStyleSheet==="function"&&("adoptedStyleSheets" in document)){
sheet=new CSSStyleSheet();
sheet.replaceSync(text);
document.adoptedStyleSheets=document.adoptedStyleSheets.concat([sheet]);
}
}catch(e){sheet=null;}
if(!sheet){
var attach=function(){
try{
el=document.createElement("style");
el.textContent=text;
(document.head||document.documentElement).appendChild(el);
}catch(e){}
};
if(document.head||document.documentElement){attach();}
else{document.addEventListener("DOMContentLoaded",attach,{once:true});}
}
handle.remove=function(){
try{
if(sheet){
document.adoptedStyleSheets=document.adoptedStyleSheets.filter(function(s){return s!==sheet;});
sheet=null;
}
if(el&&el.parentNode){
el.parentNode.removeChild(el);
el=null;
}
}catch(e){}
};
return handle;
}
function onReady(f){
if(typeof f!=="function")return;
if(document.readyState!=="loading"){setTimeout(function(){safe(f);},0);}
else{document.addEventListener("DOMContentLoaded",function(){safe(f);},{once:true});}
}
var api={
id:META.id,
title:META.title,
version:META.version,
isTop:isTop,
url:href,
log:function(){inv("log","info",fmt(arguments));},
warn:function(){inv("log","warn",fmt(arguments));},
error:function(){inv("log","error",fmt(arguments));},
storage:{
get:function(k){var v=inv("sget",k);return v===undefined?null:v;},
set:function(k,v){return inv("sset",k,v)==="1";},
remove:function(k){return inv("sdel",k)==="1";}
},
getResource:function(n){return inv("res",n);},
addStyle:addStyle,
onReady:onReady
};
var SU="coara-plugin/"+META.id+"/";
window.addEventListener("error",function(ev){
try{
var f=String(ev&&ev.filename||"");
if(f.indexOf(SU)<0)return;
rep("error",ev.message,ev.error&&ev.error.stack);
}catch(e){}
},true);
window.addEventListener("unhandledrejection",function(ev){
try{
var r=ev&&ev.reason;
var s=String(r&&r.stack||"");
if(s.indexOf(SU)<0)return;
rep("promise",(r&&r.message)?r.message:String(r),s);
}catch(e){}
},true);
var ran=false;
function run(){
if(ran)return;
ran=true;
try{
for(var i=0;i<CSS.length;i++){api.addStyle(CSS[i]);}
PLG.call(window,api);
inv("ok","","","");
}catch(e){
rep("runtime",e&&e.message?e.message:String(e),e&&e.stack);
}
}
var at=META.runAt;
if(at==="document_start"){
run();
}else if(at==="document_idle"){
var idle=function(){setTimeout(run,0);};
if(document.readyState==="complete"){idle();}
else{window.addEventListener("load",idle,{once:true});}
}else{
if(document.readyState==="loading"){document.addEventListener("DOMContentLoaded",run,{once:true});}
else{run();}
}
return"1";
})(function(coara){
%BODY%
});
//# sourceURL=coara-plugin/%ID%/plugin.js
