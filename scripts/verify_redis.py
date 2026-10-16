"""Runs real Lua scripts against an isolated temporary Redis UNIX socket. No existing data touched."""
import concurrent.futures, pathlib, subprocess, tempfile, time
root=pathlib.Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='hmdp-redis-') as d:
    socket=d+'/redis.sock'
    server=subprocess.Popen(['redis-server','--port','0','--unixsocket',socket,'--save','','--appendonly','no'],stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
    def cmd(*args):
        return subprocess.check_output(['redis-cli','-s',socket,'--raw',*map(str,args)],text=True).strip()
    def reserve(user,order,voucher=1):
        return cmd('--eval',root/'src/main/resources/seckill.lua',f'seckill:stock:{voucher}',f'seckill:order:{voucher}',f'seckill:owners:{voucher}',f'seckill:request:{order}','stream:seckill:outbox',',',user,order,voucher,100)
    try:
        for _ in range(100):
            if pathlib.Path(socket).exists(): break
            time.sleep(.02)
        cmd('SET','seckill:stock:1',10)
        with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
            results=list(pool.map(lambda u:reserve(u,1000+u),range(100)))
        assert results.count('0')==10 and cmd('GET','seckill:stock:1')=='0'
        assert cmd('XLEN','stream:seckill:outbox')=='10'
        cmd('SET','seckill:stock:2',20)
        with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
            results=list(pool.map(lambda o:reserve(7,o,2),range(2000,2020)))
        assert results.count('0')==1 and results.count('2')==19
        owner=cmd('HGET','seckill:owners:2',7)
        keys=['seckill:stock:2','seckill:owners:2','seckill:order:2']
        assert cmd('--eval',root/'src/main/resources/compensate.lua',*keys,',',7,owner)=='1'
        assert cmd('--eval',root/'src/main/resources/compensate.lua',*keys,',',7,owner)=='0'
        assert cmd('GET','seckill:stock:2')=='20'
        assert reserve(7,3000,2)=='0'
        assert cmd('--eval',root/'src/main/resources/compensate.lua',*keys,',',7,owner)=='0'
        assert cmd('GET','seckill:stock:2')=='19'
        read=root/'src/main/resources/outbox-read.lua'
        records=cmd('--eval',read,'stream:seckill:outbox',',','test-group','dead-instance','0-0').splitlines()
        assert len(records)>1 and cmd('XPENDING','stream:seckill:outbox','test-group').splitlines()[0]!='0'
        cmd('XCLAIM','stream:seckill:outbox','test-group','dead-instance',0,records[1],'IDLE',61000)
        recovered=cmd('--eval',read,'stream:seckill:outbox',',','test-group','new-instance','0-0')
        assert records[1] in recovered
        cmd('SET','seckill:stock:3',5); cmd('SET','seckill:owners:3','wrong-type')
        assert 'Invalid reservation key type' in reserve(8,4000,3)
        assert cmd('GET','seckill:stock:3')=='5'
        print('PASS: finite-stock concurrency, one-user concurrency, atomic outbox, idempotent compensation, newer-owner safety, abandoned pending recovery, wrong-type precheck')
    finally:
        server.terminate(); server.wait(timeout=5)
