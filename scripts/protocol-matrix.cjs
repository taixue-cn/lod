const fs=require('fs'),assert=require('assert'),crypto=require('crypto');
const mc=require(process.argv[2]);const port=Number(process.argv[3]);const output=process.argv[4];
const sleep=n=>new Promise(r=>setTimeout(r,n));
const results=[];const clients=[];
function vi(n){const a=[];do{let b=n&127;n>>>=7;a.push(n?b|128:b);}while(n);return Buffer.from(a);}
function i(n){const b=Buffer.alloc(4);b.writeInt32BE(n);return b;}
function long(n){const b=Buffer.alloc(8);b.writeBigInt64BE(BigInt.asIntN(64,BigInt(n)));return b;}
function str(s){const b=Buffer.from(s);return Buffer.concat([vi(b.length),b]);}
function short(n){const b=Buffer.alloc(2);b.writeInt16BE(n);return b;}
function ss(s){const b=Buffer.from(s);return Buffer.concat([short(b.length),b]);}
function pos(x,z){return (BigInt(x)<<32n)|BigInt(z>>>0);}
function reader(b){let p=0;return {vi(){let n=0,s=0,x;do{x=b[p++];n|=(x&127)<<s;s+=7;}while(x&128);return n;},byte(){return b[p++];},int(){const n=b.readInt32BE(p);p+=4;return n;},long(){const n=b.readBigInt64BE(p);p+=8;return n;},str(){const n=this.vi();const s=b.subarray(p,p+n).toString();p+=n;return s;},raw(n){const v=b.subarray(p,p+n);p+=n;return v;},rest(){return b.subarray(p);}};}
async function connect(name,version='1.21.11') {
 const client=mc.createClient({host:'127.0.0.1',port,username:name,auth:'offline',version,hideErrors:true});clients.push(client);
 const peer={client,packets:[],errors:[]};client.on('error',e=>peer.errors.push(String(e)));client.on('kick_disconnect',p=>peer.errors.push(JSON.stringify(p)));
 client.on('packet',(p,m)=>{if(m.name==='custom_payload')peer.packets.push({channel:p.channel,data:p.data,time:Date.now()});});
 client.on('position',p=>{if(p.teleportId!==undefined)client.write('teleport_confirm',{teleportId:p.teleportId});});
 await new Promise((resolve,reject)=>{client.once('login',resolve);client.once('error',reject);setTimeout(()=>reject(Error('login timeout '+name)),15000);});
 await sleep(1200);return peer;
}
function send(p,channel,data){p.client.write('custom_payload',{channel,data});}
async function wait(p,predicate,from=0,timeout=20000){const deadline=Date.now()+timeout;while(Date.now()<deadline){const hit=p.packets.slice(from).find(predicate);if(hit)return hit;if(p.errors.length)throw Error(p.errors.join('\n'));await sleep(50);}throw Error('packet timeout; '+p.packets.slice(from).map(x=>x.channel+':'+x.data.length).join(','));}
async function command(p,c){p.client.chat('/lodfixture '+c);await sleep(500);}
function handshake(p){send(p,'lss:client_info',vi(4671));send(p,'lss:handshake_c2s',Buffer.concat([vi(20),vi(1)]));}
function request(p,x=4,z=4){send(p,'lss:batch_chunk_req',Buffer.concat([vi(1),long(pos(x,z)),long(0)]));}
function column(pkt){const r=reader(pkt.data),x=r.int(),z=r.int(),dim=r.str(),stamp=r.long(),source=r.byte(),codec=r.byte(),body=r.raw(r.vi());return{x,z,dim,stamp,source,codec,body};}
function native(p,type,body,tracker){send(p,'distant_horizons:msg',Buffer.concat([short(16),short(type),...(tracker===undefined?[]:[i(tracker)]),body]));}
function dhConfig(p){native(p,4,Buffer.concat([Buffer.from([0]),i(128),i(0),i(0),i(30000000),i(2),Buffer.from([1]),i(128),Buffer.from([1]),i(128),i(2),i(0)]));}
function dhRequest(p,world,tracker){native(p,7,Buffer.concat([ss(world),long((1n<<8n)|(1n<<36n)|6n),Buffer.from([0])]),tracker);}
function model(p){return p.channel==='distant_horizons:msg'&&p.data.readInt16BE(2)===10;}
async function quiet(p,from,ms=1000){await sleep(ms);assert(!p.packets.slice(from).some(x=>x.channel==='lss:voxel_column'||model(x)),'unauthorized model payload');}
async function test(name,fn){await fn();results.push({name,status:'PASS'});console.log('PASS '+name);}
(async()=>{
 const yes=await connect('LODYes'),no=await connect('LODNo'),vanilla=await connect('LODVanilla','1.21.4');
 await test('vanilla client receives no unsolicited model traffic',async()=>quiet(vanilla,0,500));
 let body;
 await test('1.21.11 client over ViaVersion receives LSS v20 from Paper '+(process.env.ARCH_LOD_SERVER_VERSION||'1.21.4'),async()=>{handshake(yes);await wait(yes,x=>x.channel==='lss:session_config'&&reader(x.data).vi()===20);const n=yes.packets.length;request(yes);const c=column(await wait(yes,x=>x.channel==='lss:voxel_column',n));assert(c.body.includes(Buffer.from('minecraft:gold_block')));assert(!c.body.includes(Buffer.from('minecraft:diamond_block')));assert.equal(c.codec,0);body=c.body;});
 if(process.env.ARCH_LOD_LARGE_RADIUS==='True') {
   await test('server advertises a radius above 1000 chunks',async()=>{const p=yes.packets.find(x=>x.channel==='lss:session_config');const r=reader(p.data);r.vi();assert.equal(r.byte(),1);assert.equal(r.vi(),1024);});
   await test('live radius changes are advertised without reconnecting',async()=>{let n=yes.packets.length;await command(yes,'radius-small');await wait(yes,p=>{if(p.channel!=='lss:session_config')return false;const r=reader(p.data);r.vi();r.byte();return r.vi()===64;},n);n=yes.packets.length;await command(yes,'radius-large');await wait(yes,p=>{if(p.channel!=='lss:session_config')return false;const r=reader(p.data);r.vi();r.byte();return r.vi()===1024;},n);});
   await test('authorized existing terrain at 1000 chunks downloads without traveling',async()=>{const n=yes.packets.length;request(yes,1000,0);const c=column(await wait(yes,p=>p.channel==='lss:voxel_column',n));assert.equal(c.x,1000);assert.equal(c.z,0);assert(c.body.includes(Buffer.from('minecraft:gold_block')));});
   await test('requests outside the server radius cannot retrieve models',async()=>{const n=yes.packets.length;request(yes,1030,0);await quiet(yes,n);});
 }
 await test('world permission denies both LSS and native DH requests',async()=>{handshake(no);dhConfig(no);let n=no.packets.length;request(no);dhRequest(no,'world',1);await quiet(no,n);});
 await test('native DH world spoofing does not return hidden models or metadata',async()=>{dhConfig(yes);let n=yes.packets.length;native(yes,3,ss('minecraft:lod-secret'));dhRequest(yes,'lod-secret',2);await quiet(yes,n);assert(!yes.packets.slice(n).some(p=>p.channel==='distant_horizons:msg'&&p.data.readInt16BE(2)===2));});
 await test('native DH receives world bounds in chunk units',async()=>{const n=yes.packets.length;dhConfig(yes);const p=await wait(yes,p=>p.channel==='distant_horizons:msg'&&p.data.readInt16BE(2)===4,n);const r=reader(p.data);r.raw(4);r.byte();r.int();assert.equal(r.int(),0);assert.equal(r.int(),0);const radius=r.int();assert(radius>1000000&&radius<2000000,'expected chunk radius, got '+radius);});
 await test('native DH v16 builds and returns cached LOD data',async()=>{const n=yes.packets.length;dhRequest(yes,'world',3);await wait(yes,model,n,45000);});
 await test('online revocation stops cache hits on both protocols',async()=>{await command(yes,'revoke');let n=yes.packets.length;request(yes);dhRequest(yes,'world',4);await quiet(yes,n);await command(yes,'grant');});
 await test('forced presence in a denied world still cannot retrieve its models',async()=>{await command(yes,'secret');const n=yes.packets.length;request(yes);dhConfig(yes);dhRequest(yes,'lod-secret',5);await quiet(yes,n);await command(yes,'public');});
 await test('world change retires old tasks and public-world rendering recovers',async()=>{const n=yes.packets.length;request(yes);const c=column(await wait(yes,x=>x.channel==='lss:voxel_column',n));assert(c.body.includes(Buffer.from('minecraft:gold_block')));assert(!c.body.includes(Buffer.from('minecraft:diamond_block')));});
 await test('hostile lengths and unknown protocol do not disrupt gameplay',async()=>{send(yes,'lss:batch_chunk_req',Buffer.from([255,255,255,255,127]));send(yes,'lss:handshake_c2s',Buffer.concat([vi(999),vi(1)]));const n=yes.packets.length;request(yes);await wait(yes,x=>x.channel==='lss:voxel_column',n);});
 await test('client cache uploads require an explicit requested offer',async()=>{const n=yes.packets.length;send(yes,'taixue:lod_hello',vi(1));const p=await wait(yes,x=>x.channel==='taixue:lod_policy',n);const r=reader(p.data);assert.equal(r.vi(),1);assert.equal(r.str(),'taixue-arch');r.str();r.str();const nonce=r.long();assert.equal(r.byte(),1);send(yes,'taixue:lod_part',Buffer.concat([long(nonce),long(pos(6,6)),i(0),vi(2),Buffer.from([0,0])]));await sleep(200);});

 function preference(mode,entries=[]){send(yes,'taixue:lod_pref',Buffer.concat([str(mode),vi(entries.length),...entries.map(str)]));}
 await test('player blacklist stops both protocols even for authorized worlds',async()=>{preference('blacklist',['world']);await sleep(400);const n=yes.packets.length;request(yes);dhRequest(yes,'world',9);await quiet(yes,n);});
 await test('empty player whitelist denies all downloads',async()=>{preference('whitelist');await sleep(400);const n=yes.packets.length;request(yes);dhRequest(yes,'world',10);await quiet(yes,n);});
 await test('player whitelist restores an allowed world',async()=>{preference('whitelist',['world']);await sleep(400);const n=yes.packets.length;request(yes);await wait(yes,x=>x.channel==='lss:voxel_column',n);});
 await test('server whitelist overrides player preferences and cached models',async()=>{await command(yes,'globaldeny');preference('blacklist');await sleep(300);const n=yes.packets.length;request(yes);dhRequest(yes,'world',11);await quiet(yes,n);});
 await test('server blacklist also blocks an otherwise allowed world',async()=>{await command(yes,'globalblacklist');const n=yes.packets.length;request(yes);dhRequest(yes,'world',12);await quiet(yes,n);await command(yes,'globalallow');const m=yes.packets.length;request(yes);await wait(yes,x=>x.channel==='lss:voxel_column',m);});
 async function upload(payload,x=4,z=4,badHash=false){
   await sleep(1100);let n=yes.packets.length;send(yes,'taixue:lod_hello',vi(1));let p=await wait(yes,x=>x.channel==='taixue:lod_policy',n);let r=reader(p.data);r.vi();r.str();r.str();r.str();const nonce=r.long();assert.equal(r.byte(),1);
   const hash=crypto.createHash('sha256').update(payload).digest();if(badHash)hash[0]^=1;
   n=yes.packets.length;send(yes,'taixue:lod_offer',Buffer.concat([long(nonce),long(pos(x,z)),long(0),vi(payload.length),hash]));
   await wait(yes,p=>p.channel==='taixue:lod_want',n);
   for(let offset=0;offset<payload.length;offset+=28000){const part=payload.subarray(offset,offset+28000);send(yes,'taixue:lod_part',Buffer.concat([long(nonce),long(pos(x,z)),i(offset),vi(part.length),part]));}
 }
 async function stats(){await command(yes,'stats');return JSON.parse(fs.readFileSync('upload-stats.json'));}
 await test('negotiated upload of matching existing Voxy data populates shared cache',async()=>{await command(yes,'clear');await upload(body);await sleep(1000);assert.equal((await stats()).accepted,1);const n=yes.packets.length;request(yes);const c=column(await wait(yes,x=>x.channel==='lss:voxel_column',n));assert(c.body.includes(Buffer.from('minecraft:gold_block')));});
 await test('fabricated model cannot replace authoritative world data',async()=>{await upload(body,7,7);await sleep(1000);assert.equal((await stats()).rejected,1);});
 await test('upload digest mismatch is rejected',async()=>{await upload(body,7,6,true);await sleep(300);assert.equal((await stats()).rejected,2);});
 const blocked=await connect('LODBlocked');
 await test('upload blacklist overrides global trust and upload permission',async()=>{const n=blocked.packets.length;send(blocked,'taixue:lod_hello',vi(1));const p=await wait(blocked,x=>x.channel==='taixue:lod_policy',n);const r=reader(p.data);r.vi();r.str();r.str();r.str();r.long();assert.equal(r.byte(),0);});
 if(process.argv.includes('worldedit'))await test('WorldEdit edits invalidate shared terrain cache',async()=>{const n=yes.packets.length;await command(yes,'edit');await wait(yes,p=>p.channel==='lss:dirty_columns',n,10000);const m=yes.packets.length;request(yes);const c=column(await wait(yes,p=>p.channel==='lss:voxel_column',m));assert(c.body.includes(Buffer.from('minecraft:emerald_block')));assert(!c.body.includes(Buffer.from('minecraft:gold_block')));});
 if(process.argv.includes('cache'))await test('combined retention, priority, server prewarm, and unload refresh',async()=>{
  await command(yes,'cachetest');const deadline=Date.now()+240000;while(Date.now()<deadline){if(fs.existsSync('cache-test.json')){const report=JSON.parse(fs.readFileSync('cache-test.json'));assert.equal(report.status,'PASS',JSON.stringify(report));return;}await sleep(300);}throw Error('cache fixture timeout');
 });
 fs.writeFileSync(output,JSON.stringify({status:'PASS',tests:results},null,2));
})().catch(e=>{console.error(e.stack);fs.writeFileSync(output,JSON.stringify({status:'FAIL',tests:results,error:e.stack},null,2));process.exitCode=1;}).finally(async()=>{for(const c of clients)c.end();await sleep(100);process.exit(process.exitCode||0);});
