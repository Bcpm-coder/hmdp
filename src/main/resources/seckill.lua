-- All keys must be on a standalone Redis instance.
-- Validate types before writes: Redis Lua is atomic but does not roll back runtime errors.
local expected = {'string', 'set', 'hash', 'hash', 'stream'}
for i=1,5 do
 local t=redis.call('type',KEYS[i]).ok
 if t~='none' and t~=expected[i] then return redis.error_reply('Invalid reservation key type') end
end
local stock=tonumber(redis.call('get',KEYS[1]))
if not stock or stock<=0 then return 1 end
if redis.call('sismember',KEYS[2],ARGV[1])==1 then return 2 end
redis.call('xadd',KEYS[5],'*','orderId',ARGV[2],'userId',ARGV[1],'voucherId',ARGV[3],'createdAt',ARGV[4],'schemaVersion','1')
redis.call('decr',KEYS[1])
redis.call('sadd',KEYS[2],ARGV[1])
redis.call('hset',KEYS[3],ARGV[1],ARGV[2])
redis.call('hset',KEYS[4],'userId',ARGV[1],'voucherId',ARGV[3],'status','PENDING')
return 0
