#!/usr/bin/env python3
"""Fresh loopback Paper server; production worlds/configs are never loaded or changed."""
import argparse,hashlib,json,os,shutil,socket,subprocess,time,uuid
from pathlib import Path

p=argparse.ArgumentParser()
p.add_argument('--work-dir',type=Path,required=True)
p.add_argument('--minecraft-protocol',type=Path,required=True)
p.add_argument('--via-jar',type=Path)
p.add_argument('--client-version',choices=['1.21.11','26.3'],default='1.21.11')
p.add_argument('--native-mode',choices=['voxy','fallback','dh'])
p.add_argument('--mods',type=Path)
p.add_argument('--without-server-plugin',action='store_true')
p.add_argument('--worldedit',action='store_true')
p.add_argument('--cache-tests',action='store_true')
p.add_argument('--without-client-bridge',action='store_true')
a=p.parse_args();project=Path(__file__).resolve().parents[1]
root=a.work_dir.resolve()
if root.exists():raise SystemExit('work-dir must be new')
runtime=Path('/home/taixue/data/services/minecraft/servers/architecture')
root.joinpath('plugins/bStats').mkdir(parents=True)
root.joinpath('plugins/bStats/config.yml').write_text('enabled: false\n')
for name in ['libraries','cache']:
 shutil.copytree(runtime/name,root/name)
shutil.copytree(runtime/'versions/1.21.4',root/'versions/1.21.4')
shutil.copy2(runtime/'paper-1.21.4-232.jar',root/'server.jar')
for src in [project/'server/build/libs/ArchLOD-0.2.0.jar',project/'server/build/libs/ArchLODFixture.jar',(a.via_jar or runtime/'plugins/ViaVersion-5.7.0.jar')]:
 if a.without_server_plugin and src.name.startswith('ArchLOD'):continue
 shutil.copy2(src,root/'plugins'/src.name)
if a.worldedit:
 shutil.copy2(runtime/'plugins/FastAsyncWorldEdit-Paper-2.15.1-SNAPSHOT-1282.jar',root/'plugins/FastAsyncWorldEdit.jar')
 (root/'plugins/FastAsyncWorldEdit').mkdir()
 (root/'plugins/FastAsyncWorldEdit/config.yml').write_text('extent:\n  allowed-plugins:\n  - "cc.taixue.lod.server.WorldEditInvalidation$1"\n')
cfg=project.joinpath('server/src/main/resources/config.yml').read_text().replace('upload:\n  enabled: false','upload:\n  enabled: true').replace('  allowed-players: []','  allowed-players: ["*"]').replace('  blocked-players: []','  blocked-players: ["LODBlocked"]').replace('  cooldown-seconds: 5','  cooldown-seconds: 1').replace('  refresh-on-unload: true','  refresh-on-unload: false')
root.joinpath('plugins/ArchLOD').mkdir();root.joinpath('plugins/ArchLOD/config.yml').write_text(cfg)
with socket.socket() as s:s.bind(('127.0.0.1',0));port=s.getsockname()[1]
root.joinpath('eula.txt').write_text('eula=true\n')
root.joinpath('server.properties').write_text(f'''server-ip=127.0.0.1
server-port={port}
online-mode=false
white-list=true
enforce-whitelist=true
enforce-secure-profile=false
max-players=8
level-name=world
level-type=minecraft:flat
generator-settings={{"biome":"minecraft:plains","layers":[{{"block":"minecraft:bedrock","height":1}},{{"block":"minecraft:stone","height":4}}],"features":false}}
generate-structures=false
allow-nether=false
spawn-protection=0
view-distance=2
simulation-distance=2
max-tick-time=120000
enable-rcon=false
enable-query=false
network-compression-threshold=256
''')
root.joinpath('bukkit.yml').write_text('settings:\n  allow-end: false\n')
names=['LODYes','LODNo','LODBlocked','LODVanilla','LODNative']
root.joinpath('whitelist.json').write_text(json.dumps([{'name':n,'uuid':str(uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:'+n).encode()).digest(),version=3))} for n in names]))
java=shutil.which('java');report={'production_services':False,'port':port}
with root.joinpath('console.log').open('w') as log:
 server=subprocess.Popen([java,'-Xms256m','-Xmx1536m','-XX:ActiveProcessorCount=2','-Dterminal.jline=false','-Dterminal.ansi=false','-jar','server.jar','--nogui'],cwd=root,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,text=True)
 try:
  deadline=time.monotonic()+180
  while time.monotonic()<deadline and server.poll() is None:
   if ('Done (' if a.without_server_plugin else 'LOD_FIXTURE_READY') in root.joinpath('console.log').read_text(errors='replace'):break
   time.sleep(.5)
  else:raise RuntimeError('fixture did not start')
  time.sleep(2)
  if a.native_mode:
   env=dict(os.environ)
   env['ARCH_LOD_CLIENT_VERSION']=a.client_version
   if a.client_version=='26.3':env.setdefault('SDL_VIDEO_FORCE_EGL','1')
   env.update(ARCH_LOD_NATIVE_TEST=a.native_mode,ARCH_LOD_NATIVE_REPORT=str(root/'native.json'),ARCH_LOD_NATIVE_SERVER=f'127.0.0.1:{port}',ARCH_LOD_RUN_DIR=str(root/'native-client'),DISPLAY=':91',MESA_GL_VERSION_OVERRIDE='4.6',MESA_GLSL_VERSION_OVERRIDE='460',LIBGL_ALWAYS_SOFTWARE='true',ALSOFT_DRIVERS='null')
   if a.without_client_bridge:env['ARCH_LOD_OMIT_BRIDGE']='1'
   if a.mods:env['ARCH_LOD_TEST_MODS']=str(a.mods.resolve())
   native=root/'native-client';(native/'config').mkdir(parents=True)
   (native/'options.txt').write_text('onboardAccessibility:false\nrenderDistance:2\nsimulationDistance:2\nmaxFps:30\nsoundCategory_master:0.0\n')
   (native/'config/taixue-lod-client.json').write_text('{"shareLocalCache":true,"trustedServers":["127.0.0.1"]}\n')
   with (root/'native-client.log').open('w') as client_log:
    result=subprocess.run(['python3',str(project/'scripts/native-client.py')],cwd=project,env=env,stdout=client_log,stderr=subprocess.STDOUT,timeout=600)
   if not root.joinpath('native.json').is_file():raise RuntimeError('native client produced no verification report')
   report['native']=json.loads(root.joinpath('native.json').read_text())
   if result.returncode or report['native'].get('status')!='PASS':raise RuntimeError('native client verification failed')
  else:
   result=subprocess.run(['node',str(project/'scripts/protocol-matrix.cjs'),str(a.minecraft_protocol.resolve()),str(port),str(root/'matrix.json')]+(['worldedit'] if a.worldedit else [])+(['cache'] if a.cache_tests else []),cwd=root,timeout=300,text=True,capture_output=True)
   root.joinpath('clients.log').write_text(result.stdout+result.stderr)
   report.update({'client_returncode':result.returncode,'client_stdout':result.stdout[-2000:]})
   if result.returncode:raise RuntimeError(result.stderr[-2000:] or result.stdout[-2000:])
   report['matrix']=json.loads(root.joinpath('matrix.json').read_text())
  report['voxy_cache_files']=len(list(root.joinpath('plugins/ArchLOD/voxy-cache').rglob('*.gz')))
  if not a.without_server_plugin:report['dh_database_bytes']=root.joinpath('plugins/ArchLOD/data.sqlite').stat().st_size
  report['status']='PASS'
 except Exception as e:
  report['status']='FAIL';report['error']=str(e);raise
 finally:
  if server.poll() is None:
   try:server.stdin.write('stop\n');server.stdin.flush();server.wait(timeout=30)
   except Exception:server.terminate();server.wait(timeout=10)
  root.joinpath('report.json').write_text(json.dumps(report,indent=2)+'\n')
  for copied in ['libraries','cache','versions']:shutil.rmtree(root/copied,ignore_errors=True)
  root.joinpath('server.jar').unlink(missing_ok=True)
print(json.dumps(report,indent=2))
