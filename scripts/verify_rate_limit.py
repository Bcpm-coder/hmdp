"""Real Redis sliding-window verification on an isolated UNIX socket."""
import concurrent.futures, pathlib, subprocess, tempfile, time, uuid
root=pathlib.Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='hmdp-limit-') as d:
    socket=d+'/redis.sock'
    server=subprocess.Popen(['redis-server','--port','0','--unixsocket',socket,'--save','','--appendonly','no'],stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
    def cmd(*args):
        return subprocess.check_output(['redis-cli','-s',socket,'--raw',*map(str,args)],text=True).strip()
    def check(key='rate:test:USER:1',window=10000,maximum=3):
        return cmd('--eval',root/'src/main/resources/rate-limit.lua',key,',',window,maximum,uuid.uuid4())
    try:
        for _ in range(100):
            if pathlib.Path(socket).exists():break
            if server.poll() is not None:raise RuntimeError(server.stderr.read().decode())
            time.sleep(.02)
        with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:
            results=list(pool.map(lambda _:check(),range(100)))
        assert results.count('1')==3 and results.count('0')==97
        assert cmd('ZCARD','rate:test:USER:1')=='3'
        assert check('rate:test:USER:2')=='1'
        assert check('rate:test:IP:192.0.2.1')=='1'
        assert check('rate:test:GLOBAL:all')=='1'
        seconds,micros=map(int,cmd('TIME').splitlines()); now=seconds*1000+micros//1000
        cmd('ZADD','rate:boundary',now-11000,'expired',now,'recent')
        assert check('rate:boundary')=='1'
        assert cmd('ZCARD','rate:boundary')=='2' and not cmd('ZSCORE','rate:boundary','expired')
        assert check('rate:expiry',200,1)=='1'
        initial=int(cmd('PTTL','rate:expiry'));time.sleep(.06)
        assert check('rate:expiry',200,1)=='0'
        assert int(cmd('PTTL','rate:expiry'))<initial
        time.sleep(.25);assert cmd('EXISTS','rate:expiry')=='0'
        assert check('rate:expiry',200,1)=='1'
        print('PASS: concurrent quota, denied calls not recorded, identity isolation, stale record removal, denial does not renew TTL, expiry recovery')
    finally:
        server.terminate();server.wait(timeout=5)
