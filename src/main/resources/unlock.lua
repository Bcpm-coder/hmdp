local id = redis.call('get', KEYS[1])
if(ARGV[1]==id) then
    return redis.call('del', KEYS[1])
end
return 0