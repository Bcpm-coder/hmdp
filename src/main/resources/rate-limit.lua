-- Window is (now - windowMillis, now]. Server TIME avoids app-instance clock differences.
local window=tonumber(ARGV[1])
local maximum=tonumber(ARGV[2])
if not window or window<=0 or not maximum or maximum<=0 or not ARGV[3] then
 return redis.error_reply('Invalid limit arguments')
end
redis.replicate_commands()
local t=redis.call('time')
local now=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)
redis.call('zremrangebyscore',KEYS[1],'-inf',now-window)
if redis.call('zcard',KEYS[1])>=maximum then return 0 end
redis.call('zadd',KEYS[1],now,ARGV[3])
redis.call('pexpire',KEYS[1],window)
return 1
