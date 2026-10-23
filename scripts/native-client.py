#!/usr/bin/env python3
"""Run distributed Fabric jars in production mappings against the loopback fixture."""
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import os,json,urllib.request,hashlib,subprocess,shutil,zipfile,fcntl
project=Path(__file__).resolve().parents[1]
cache=project.parent/'artifacts/arch-lod-20261023/native-runtime';cache.mkdir(exist_ok=True)
gradle=Path.home()/'.gradle/caches'
version=os.environ.get('ARCH_LOD_CLIENT_VERSION','1.21.11')
modern=version=='26.3'
loader='0.20.1' if modern else '0.18.4'
api='0.165.0+26.3' if modern else '0.141.3+1.21.11'
client_project=project/'client-26.3' if modern else project/'client'
client_jar='LodClient-0.2.0+mc26.3' if modern else 'LodClient-0.2.0'
info=json.loads((gradle/f'fabric-loom/{version}/mojang_minecraft_info.json').read_text())
profile=json.load(urllib.request.urlopen(f'https://meta.fabricmc.net/v2/versions/loader/{version}/{loader}/profile/json'))
def fetch(item):
 url=item['url'];target=cache/url.rsplit('/',1)[1]
 if target.exists() and (not item.get('sha1') or hashlib.sha1(target.read_bytes()).hexdigest()==item['sha1']):return target
 matches=list(gradle.glob('modules-2/files-2.1/**/'+target.name))
 for source in matches:
  if not item.get('sha1') or hashlib.sha1(source.read_bytes()).hexdigest()==item['sha1']:
   shutil.copy2(source,target);return target
 with urllib.request.urlopen(url) as r:body=r.read()
 if item.get('sha1') and hashlib.sha1(body).hexdigest()!=item['sha1']:raise RuntimeError('checksum '+target.name)
 target.write_bytes(body);return target
libs=[]
for lib in info['libraries']:
 rules=lib.get('rules',[])
 if rules:
  allowed=False
  for rule in rules:
   if rule.get('os',{}).get('name','linux')=='linux':allowed=rule['action']=='allow'
  if not allowed:continue
 if 'artifact' in lib.get('downloads',{}):libs.append(lib['downloads']['artifact'])
for lib in profile['libraries']:
 group,name,lib_version=lib['name'].split(':');url=lib.get('url','https://maven.fabricmc.net/')+group.replace('.','/')+'/'+name+'/'+lib_version+'/'+name+'-'+lib_version+'.jar';libs.append({'url':url})
with (cache/'.download.lock').open('w') as lock:
 fcntl.flock(lock,fcntl.LOCK_EX)
 with ThreadPoolExecutor(max_workers=8) as pool:classpath=list(pool.map(fetch,libs))
classpath.append(gradle/f'fabric-loom/{version}/minecraft-client.jar')
run=Path(os.environ['ARCH_LOD_RUN_DIR']);mods=run/'mods';mods.mkdir(parents=True,exist_ok=True)
for src in ([client_project/f'build/libs/{client_jar}.jar'] if not os.environ.get('ARCH_LOD_OMIT_BRIDGE') else [])+[client_project/f'build/libs/{client_jar}-fixture.jar',next(gradle.glob(f'modules-2/files-2.1/net.fabricmc.fabric-api/fabric-api/{api}/*/*.jar'))]:shutil.copy2(src,mods/src.name)
for src in Path(os.environ.get('ARCH_LOD_TEST_MODS',str(cache/'empty'))).glob('*.jar'):shutil.copy2(src,mods/src.name)
assets=gradle/'fabric-loom/assets'
java='/data/taixue/environments/java/jdk/jdk-25.0.2/bin/java' if modern else 'java'
cmd=[java,'-Xmx1536m','-XX:ActiveProcessorCount=4','-Darchlod.fixture.mode='+os.environ['ARCH_LOD_NATIVE_TEST'],'-Darchlod.fixture.report='+os.environ['ARCH_LOD_NATIVE_REPORT'],'-cp',os.pathsep.join(map(str,classpath)),profile['mainClass'],'--username','LODNative','--version',version,'--gameDir',str(run),'--assetsDir',str(assets),'--assetIndex',version+'-'+info['assetIndex']['id'],'--accessToken','0','--userType','legacy','--width','640','--height','480']
raise SystemExit(subprocess.call(cmd,cwd=run))
