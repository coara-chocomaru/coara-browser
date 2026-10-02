(function(){
var B=window["%BRIDGE%"];
var T="%TOKEN%";
var EXP=%EXPECTED%;
if(!B||!EXP.length)return;
var isTop=false;
try{isTop=window.top===window;}catch(e){}
function rx(list,u){
for(var i=0;i<list.length;i++){
try{if(new RegExp(list[i]).test(u))return true;}catch(e){}
}
return false;
}
function check(){
var rs=window.__coaraPlgReady||{};
var u="";
try{u=String(location.href);}catch(e){}
for(var i=0;i<EXP.length;i++){
var e=EXP[i];
if(!e.allFrames&&!isTop)continue;
if(e.excludes.length&&rx(e.excludes,u))continue;
if(!rx(e.matches,u))continue;
if(!rs[e.key]){
try{
B.invoke(T,e.id,"report","load","プラグインのスクリプトが読み込まれませんでした(構文エラーの可能性)",JSON.stringify({url:u,stack:""}));
}catch(x){}
}
}
}
function arm(){setTimeout(check,1500);}
if(document.readyState==="complete"){arm();}
else{window.addEventListener("load",arm,{once:true});}
})();
