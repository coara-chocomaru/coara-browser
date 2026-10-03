var browser=(function(){
var id=coara.id;
var manifestCache=null;
var runtimeListeners=[];
var changeListeners=[];
var sessionStore={};
function clone(v){
if(v===undefined)return undefined;
try{return JSON.parse(JSON.stringify(v));}catch(e){return v;}
}
function ret(value,cb){
if(typeof cb==="function"){setTimeout(function(){try{cb(value);}catch(e){coara._rep("runtime",e&&e.message?e.message:String(e),e&&e.stack);}},0);return undefined;}
return Promise.resolve(value);
}
function manifest(){
if(manifestCache===null){
var t=coara.getResource("manifest.json");
try{manifestCache=t?JSON.parse(t):{};}catch(e){manifestCache={};}
}
return manifestCache;
}
function getURL(p){
p=String(p==null?"":p).replace(/^\/+/,"");
var u=p?coara.getResourceURL(p):null;
return u||("chrome-extension://"+id+"/"+p);
}
function fire(changes,area){
for(var i=0;i<changeListeners.length;i++){
try{changeListeners[i](clone(changes),area);}catch(e){}
}
}
function makeArea(name,store){
var P="ext:";
function readOne(k){
if(store){return clone(store[k]);}
var raw=coara.storage.get(P+k);
if(raw===null||raw===undefined)return undefined;
try{return JSON.parse(raw);}catch(e){return undefined;}
}
function writeOne(k,v){
if(store){store[k]=clone(v);return true;}
return coara.storage.set(P+k,JSON.stringify(v));
}
function removeOne(k){
if(store){delete store[k];return;}
coara.storage.remove(P+k);
}
function allKeys(){
if(store)return Object.keys(store);
var ks=coara.storage.keys(),out=[];
for(var i=0;i<ks.length;i++){if(ks[i].indexOf(P)===0)out.push(ks[i].substring(P.length));}
return out;
}
return{
get:function(keys,cb){
if(typeof keys==="function"){cb=keys;keys=null;}
var res={},i;
if(keys==null){
var ks=allKeys();
for(i=0;i<ks.length;i++)res[ks[i]]=readOne(ks[i]);
}else if(typeof keys==="string"){
var v=readOne(keys);
if(v!==undefined)res[keys]=v;
}else if(Array.isArray(keys)){
for(i=0;i<keys.length;i++){
var v2=readOne(keys[i]);
if(v2!==undefined)res[keys[i]]=v2;
}
}else if(typeof keys==="object"){
for(var k in keys){
var v3=readOne(k);
res[k]=v3===undefined?clone(keys[k]):v3;
}
}
return ret(res,cb);
},
set:function(items,cb){
var changes={};
for(var k in items){
var old=readOne(k);
writeOne(k,items[k]);
changes[k]={oldValue:old,newValue:clone(items[k])};
}
fire(changes,name);
return ret(undefined,cb);
},
remove:function(keys,cb){
var list=Array.isArray(keys)?keys:[keys];
var changes={};
for(var i=0;i<list.length;i++){
var old=readOne(list[i]);
removeOne(list[i]);
changes[list[i]]={oldValue:old};
}
fire(changes,name);
return ret(undefined,cb);
},
clear:function(cb){
var ks=allKeys(),changes={};
for(var i=0;i<ks.length;i++){
changes[ks[i]]={oldValue:readOne(ks[i])};
removeOne(ks[i]);
}
fire(changes,name);
return ret(undefined,cb);
},
getBytesInUse:function(keys,cb){
if(typeof keys==="function"){cb=keys;}
var ks=allKeys(),n=0;
for(var i=0;i<ks.length;i++){n+=JSON.stringify(readOne(ks[i])||"").length;}
return ret(n,typeof keys==="function"?keys:cb);
}
};
}
function event(list){
return{
addListener:function(f){if(typeof f==="function"&&list.indexOf(f)<0)list.push(f);},
removeListener:function(f){var i=list.indexOf(f);if(i>=0)list.splice(i,1);},
hasListener:function(f){return list.indexOf(f)>=0;}
};
}
function sendMessage(){
var args=Array.prototype.slice.call(arguments);
var cb=typeof args[args.length-1]==="function"?args.pop():null;
var msg=(args.length>=2&&typeof args[0]==="string")?args[1]:args[0];
var p=new Promise(function(resolve){
var done=false,pending=0;
function respond(v){if(!done){done=true;resolve(v);}}
var sender={id:id,url:String(location.href),tab:{id:1,url:String(location.href),active:true}};
var ls=runtimeListeners.slice();
for(var i=0;i<ls.length&&!done;i++){
var r;
try{r=ls[i](clone(msg),sender,function(v){respond(clone(v));});}catch(e){continue;}
if(r===true){pending++;}
else if(r&&typeof r.then==="function"){pending++;r.then(function(v){respond(v);},function(){});}
}
if(!done){setTimeout(function(){respond(undefined);},pending?5000:0);}
});
if(cb){p.then(cb);return undefined;}
return p;
}
var onChanged=event(changeListeners);
var local=makeArea("local",null);
var sync=makeArea("sync",null);
var session=makeArea("session",sessionStore);
var lang=(coara.uiLanguage()||"en").replace("_","-");
var ext={
runtime:{
id:id,
lastError:null,
getURL:getURL,
getManifest:function(){return clone(manifest());},
sendMessage:sendMessage,
onMessage:event(runtimeListeners),
onInstalled:event([]),
onStartup:event([]),
onConnect:event([]),
connect:function(){
return{name:"",postMessage:function(){},disconnect:function(){},onMessage:event([]),onDisconnect:event([])};
},
getPlatformInfo:function(cb){return ret({os:"android",arch:"arm",nacl_arch:"arm"},cb);},
getBrowserInfo:function(cb){return ret({name:"CoaraBrowser",vendor:"coara",version:"1.0",buildID:"1"},cb);},
openOptionsPage:function(cb){return ret(undefined,cb);}
},
extension:{
getURL:getURL,
getBackgroundPage:function(){return null;},
inIncognitoContext:false
},
storage:{local:local,sync:sync,managed:makeArea("managed",{}),session:session,onChanged:onChanged},
i18n:{
getMessage:function(key,subs){
var arr=subs==null?null:(Array.isArray(subs)?subs:[subs]);
return coara.i18n(String(key),arr);
},
getUILanguage:function(){return lang;},
getAcceptLanguages:function(cb){return ret([lang],cb);}
},
tabs:{
create:function(props,cb){
var u=props&&props.url?String(props.url):"";
if(u)coara.openTab(u);
return ret({id:2,url:u,active:!(props&&props.active===false)},cb);
},
query:function(q,cb){
if(typeof q==="function"){cb=q;}
return ret([{id:1,url:String(location.href),title:String(document.title||""),active:true}],typeof q==="function"?q:cb);
},
getCurrent:function(cb){return ret({id:1,url:String(location.href),active:true},cb);},
sendMessage:function(tabId,msg,a,b){return sendMessage(msg,typeof a==="function"?a:b);},
update:function(tabId,props,cb){
if(typeof tabId==="object"){cb=props;props=tabId;}
if(props&&props.url&&/^https?:/i.test(String(props.url))){location.href=String(props.url);}
return ret({id:1,url:String(location.href)},cb);
}
},
notifications:{
create:function(a,b,c){
var nid=typeof a==="string"?a:"n"+Date.now();
var opts=typeof a==="string"?b:a;
var cb=typeof b==="function"?b:c;
if(opts)coara.notify(opts.message==null?"":String(opts.message),opts.title==null?undefined:String(opts.title));
return ret(nid,typeof cb==="function"?cb:undefined);
},
clear:function(nid,cb){return ret(true,cb);},
onClicked:event([]),
onClosed:event([])
}
};
return ext;
})();
var chrome=browser;
