-- Redis >= 6.2. Nonblocking reads; abandoned records are claimed after 60s.
local group=ARGV[1]
local exists=redis.pcall('xgroup','create',KEYS[1],group,'0','MKSTREAM')
if type(exists)=='table' and exists.err and not string.find(exists.err,'BUSYGROUP') then return redis.error_reply(exists.err) end
local claimed=redis.call('xautoclaim',KEYS[1],group,ARGV[2],60000,ARGV[3],'COUNT',20)
local out={claimed[1]}
local function append(records)
 for _,r in ipairs(records) do
  local fields={}
  for i=1,#r[2],2 do fields[r[2][i]]=r[2][i+1] end
  table.insert(out,r[1]); table.insert(out,cjson.encode(fields))
 end
end
append(claimed[2])
local fresh=redis.call('xreadgroup','GROUP',group,ARGV[2],'COUNT',20,'STREAMS',KEYS[1],'>')
if fresh then append(fresh[1][2]) end
return out
