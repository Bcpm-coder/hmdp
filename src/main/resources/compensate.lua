local owner=redis.call('hget',KEYS[2],ARGV[1])
if owner~=ARGV[2] then return 0 end
local stock=tonumber(redis.call('get',KEYS[1]))
if not stock then return redis.error_reply('Missing stock; manual repair required') end
local t=redis.call('type',KEYS[3]).ok
if t~='none' and t~='set' then return redis.error_reply('Invalid purchasers type') end
redis.call('incr',KEYS[1])
redis.call('hdel',KEYS[2],ARGV[1])
redis.call('srem',KEYS[3],ARGV[1])
return 1
