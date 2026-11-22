"""Windows deployment checks using fake credentials, mock Docker, and stub Maven only."""
import json, os, shutil, subprocess, tempfile
from pathlib import Path
repo=Path(__file__).resolve().parents[2]
bash=os.environ.get("BASH_EXECUTABLE") or shutil.which("bash")
if os.name != "nt" or not bash or Path(bash).parent.name.lower() == "system32":
    raise SystemExit("Run on Windows; set BASH_EXECUTABLE to Git Bash bin/bash.exe.")
fixture=Path(tempfile.mkdtemp(prefix="miaosha deploy verify "))
checks=[]
def check(value,label):
    assert value,label
    checks.append(label)
env_text="JAVA_OPTS='-Xms96m -Xmx96m'\nMYSQL_HOST=outside-db.invalid\nMYSQL_PORT=13307\nMYSQL_DATABASE=fixture_db\nMYSQL_USERNAME=fixture_user\nMYSQL_PASSWORD=fixture_mysql_only\nREDIS_HOST=outside-redis.invalid\nREDIS_PORT=16380\nREDIS_PASSWORD=fixture_redis_only\nMQ_NAMESERVER_ADDR=outside-mq.invalid:19876\nMQ_TOPICNAME=fixture_stock\n"
shell_root=fixture/"shell fixture"
(shell_root/"scripts").mkdir(parents=True)
(shell_root/"sql").mkdir()
(shell_root/"miaosha.jar").write_text("fixture only")
(shell_root/"application.yml").write_text("logging: {}\n")
(shell_root/".env").write_text(env_text)
names=("start-app.sh","start-app2.sh","start-mysql.sh","start-redis.sh")
for name in names:
    shutil.copyfile(repo/"scripts"/name,shell_root/"scripts"/name)
    result=subprocess.run([bash,"-n",str(shell_root/"scripts"/name)],capture_output=True)
    check(result.returncode==0,name+": syntax")
function=r"""
docker() {
 printf '%s\0' '__CALL__' "$@" '__END__' >> "$MOCK_DOCKER_LOG"
 if [ "$MOCK_BUILD_FAILURE" = 1 ] && [ "$1" = build ]; then return 9; fi
 return 0
}
export -f docker
bash "$1"
"""
def run_shell(name,fail=False):
    log=fixture/(name+".calls")
    log.write_bytes(b"")
    env=os.environ.copy()
    env.pop("REDIS_PASSWORD", None)
    env.update(MOCK_DOCKER_LOG=str(log).replace("\\","/"),MOCK_BUILD_FAILURE="1" if fail else "",MYSQL_PASSWORD="exported_value_must_not_win")
    result=subprocess.run([bash,"-c",function,"selfcheck",str(shell_root/"scripts"/name)],env=env,capture_output=True,timeout=30)
    calls=[]; current=None
    for value in log.read_bytes().decode().split("\0"):
        if value=="__CALL__": current=[]
        elif value=="__END__": calls.append(current);current=None
        elif current is not None: current.append(value)
    return result,calls
for name,port in (("start-app.sh","18091:18091"),("start-app2.sh","18092:18091")):
    result,calls=run_shell(name)
    check(result.returncode==0,name+": mock execution")
    run=next(call for call in calls if call[0]=="run")
    check(port in run,name+": instance port")
    check("SPRING_REDIS_HOST=miaosha-redis" in run and "SPRING_REDIS_PORT=6379" in run,name+": internal Redis route")
    check("MQ_NAMESERVER_ADDR=miaosha-rocketmq-namesrv:9876" in run,name+": internal MQ route")
    check("SPRING_DATASOURCE_PASSWORD=fixture_mysql_only" in run and "SPRING_REDIS_PASSWORD=fixture_redis_only" in run,name+": .env precedence")
    check(any(arg.startswith("SPRING_DATASOURCE_URL=jdbc:mysql://miaosha-mysql:3306/fixture_db") for arg in run),name+": internal DB route")
    check(any("shell fixture/application.yml:" in arg for arg in run),name+": path with spaces")
result,calls=run_shell("start-mysql.sh");run=next(call for call in calls if call[0]=="run")
check(result.returncode==0 and "13307:3306" in run and "MYSQL_ROOT_PASSWORD=fixture_mysql_only" in run,"MySQL: credentials and mapped port")
result,calls=run_shell("start-redis.sh");run=next(call for call in calls if call[0]=="run")
check(result.returncode==0 and "16380:6379" in run and run[run.index("--requirepass")+1]=="fixture_redis_only","Redis: credentials and mapped port")
(shell_root/".env").write_text(env_text.replace("REDIS_PASSWORD=fixture_redis_only\n",""))
result,calls=run_shell("start-app.sh")
check(result.returncode!=0 and not calls,"Missing credentials: no Docker calls")
(shell_root/".env").write_text(env_text)
result,calls=run_shell("start-app.sh",True)
check(result.returncode!=0 and not any(call[0] in ("stop","rm","run") for call in calls),"Build failure: existing app untouched")

package_root=fixture/"package fixture";package_root.mkdir()
for name in ("package.bat","Dockerfile","docker-compose.yml",".dockerignore"):shutil.copyfile(repo/name,package_root/name)
(package_root/"sql").mkdir()
(package_root/"sql"/"miaosha.sql").write_text("-- fixture only\n")
(package_root/".env").write_text(env_text)
(package_root/"application.yml").write_text("logging: {}\n")
stub=fixture/"stub tools";(stub/"bin").mkdir(parents=True)
(stub/"bin"/"java.exe").write_bytes(b"stub existence check only")
(stub/"bin"/"mvn.cmd").write_bytes(b"@echo off\r\nif defined STUB_MAVEN_FAILURE exit /b 9\r\nif not exist target mkdir target\r\n> target\\miaosha-1.0-SNAPSHOT.jar echo fixture jar\r\nexit /b 0\r\n")
package_env=os.environ.copy();package_env.update(JAVA_HOME=str(stub),MAVEN_HOME=str(stub))
def run_package(env=None):
    return subprocess.run(["cmd.exe","/d","/c","call package.bat"],cwd=package_root,env=env or package_env,capture_output=True,timeout=60)
result=run_package()
if result.returncode:
    print(result.stdout.decode("utf-8",errors="replace"))
    print(result.stderr.decode("utf-8",errors="replace"))
check(result.returncode==0,"Batch: stub Maven succeeds")
deploy=package_root/"target"/"deploy"
check(all((deploy/name).is_file() for name in ("miaosha.jar","Dockerfile","docker-compose.yml",".dockerignore",".env","application.yml","sql/miaosha.sql")),"Batch: complete deployment package")
(deploy/"stale-marker").write_text("fixture")
result=run_package();check(result.returncode==0 and not (deploy/"stale-marker").exists(),"Batch: safe cleanup")
failure_env=package_env.copy();failure_env["STUB_MAVEN_FAILURE"]="1"
check(run_package(failure_env).returncode!=0,"Batch: build failure is nonzero")
source=package_root/"sql"/"miaosha.sql";data=source.read_bytes();source.unlink()
(deploy/"preserve-marker").write_text("fixture")
result=run_package();check(result.returncode!=0 and (deploy/"preserve-marker").exists(),"Batch: missing input preserves previous package")
source.write_bytes(data)
dockerfile=package_root/"Dockerfile";data=dockerfile.read_bytes();dockerfile.unlink();dockerfile.mkdir()
check(run_package().returncode!=0,"Batch: copy failure is nonzero")
dockerfile.rmdir();dockerfile.write_bytes(data)
docker_cli=shutil.which("docker")
if docker_cli:
    result=subprocess.run([docker_cli,"compose","--env-file",str(package_root/".env"),"-f",str(repo/"docker-compose.yml"),"config","--quiet"],capture_output=True,timeout=30)
    check(result.returncode==0,"Compose: offline config validation")
print(json.dumps({"passed":len(checks),"checks":checks,"fixture":str(fixture)},ensure_ascii=False,indent=2))
