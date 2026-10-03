var unsafeWindow=window;
var __gmMeta=coara.meta||{};
var GM_info={
script:{name:__gmMeta.title||"",namespace:__gmMeta.namespace||"",version:__gmMeta.version||"",description:__gmMeta.description||"",author:__gmMeta.author||"",homepage:__gmMeta.homepage||"",matches:__gmMeta.rawMatches||[],excludes:__gmMeta.rawExcludes||[],"run-at":__gmMeta.runAt||"",grant:__gmMeta.permissions||[],resources:[],uuid:__gmMeta.id||""},
scriptMetaStr:"",
scriptHandler:"CoaraBrowser",
version:"1.0",
isIncognito:false,
isFirstPartyIsolation:false
};
var __gmListeners={};
var __gmListenerSeq=0;
function __gmFire(name,oldV,newV){
var l=__gmListeners[name];
if(!l)return;
for(var id in l){
try{l[id](name,oldV,newV,false);}catch(e){coara._rep("runtime",e&&e.message?e.message:String(e),e&&e.stack);}
}
}
function GM_log(){coara.log.apply(null,arguments);}
function GM_addStyle(css){return coara.addStyle(css);}
function GM_getValue(k,d){
var raw=coara.storage.get(String(k));
if(raw===null||raw===undefined)return d;
try{return JSON.parse(raw);}catch(e){return raw;}
}
function GM_setValue(k,v){
k=String(k);
if(v===undefined){return GM_deleteValue(k);}
var old=GM_getValue(k);
var ok=coara.storage.set(k,JSON.stringify(v));
if(ok)__gmFire(k,old,v);
return ok;
}
function GM_deleteValue(k){
k=String(k);
var old=GM_getValue(k);
coara.storage.remove(k);
__gmFire(k,old,undefined);
}
function GM_listValues(){return coara.storage.keys();}
function GM_addValueChangeListener(name,fn){
if(typeof fn!=="function")return 0;
var id=++__gmListenerSeq;
(__gmListeners[name]=__gmListeners[name]||{})[id]=fn;
return id;
}
function GM_removeValueChangeListener(id){
for(var n in __gmListeners){
if(__gmListeners[n][id]){delete __gmListeners[n][id];}
}
}
function GM_getResourceText(n){return coara.getResource(String(n));}
function GM_getResourceURL(n){return coara.getResourceURL(String(n));}
function GM_xmlhttpRequest(d){return coara.xhr(d);}
function GM_setClipboard(text){coara.copy(String(text));}
function GM_notification(a,b,c,d){
var o=(a&&typeof a==="object")?a:{text:a,title:b,image:c,onclick:d};
coara.notify(o.text==null?"":String(o.text),o.title==null?undefined:String(o.title));
if(typeof o.ondone==="function"){setTimeout(function(){try{o.ondone();}catch(e){}},0);}
}
function GM_openInTab(url,opt){
coara.openTab(String(url));
return{closed:false,close:function(){},onclose:null};
}
function GM_registerMenuCommand(name,fn,key){return coara.registerCommand(name,fn);}
function GM_unregisterMenuCommand(id){coara.unregisterCommand(id);}
function GM_download(a,b){
var url=(a&&typeof a==="object")?a.url:a;
coara.openTab(String(url));
}
function __gmP(f){
return function(){
var args=arguments;
return new Promise(function(res,rej){
try{res(f.apply(null,args));}catch(e){rej(e);}
});
};
}
var GM={
info:GM_info,
log:GM_log,
getValue:__gmP(GM_getValue),
setValue:__gmP(GM_setValue),
deleteValue:__gmP(GM_deleteValue),
listValues:__gmP(GM_listValues),
addValueChangeListener:GM_addValueChangeListener,
removeValueChangeListener:GM_removeValueChangeListener,
getResourceText:__gmP(GM_getResourceText),
getResourceUrl:__gmP(GM_getResourceURL),
getResourceURL:__gmP(GM_getResourceURL),
addStyle:__gmP(GM_addStyle),
setClipboard:__gmP(GM_setClipboard),
notification:__gmP(GM_notification),
openInTab:__gmP(GM_openInTab),
registerMenuCommand:GM_registerMenuCommand,
unregisterMenuCommand:GM_unregisterMenuCommand,
download:__gmP(GM_download),
xmlHttpRequest:function(d){
var h;
var p=new Promise(function(res,rej){
var o={};
for(var k in d){o[k]=d[k];}
o.onload=function(r){res(r);};
o.onerror=function(r){rej(r);};
o.onabort=function(r){rej(r);};
o.ontimeout=function(r){rej(r);};
h=coara.xhr(o);
});
p.abort=function(){if(h)h.abort();};
return p;
}
};
GM.xmlhttpRequest=GM.xmlHttpRequest;
