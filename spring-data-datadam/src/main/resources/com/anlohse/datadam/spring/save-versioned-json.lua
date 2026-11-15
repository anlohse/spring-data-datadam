-- ARGV: version JSONPath, expected version (empty on create), operation, new JSON, TTL milliseconds (or empty).
-- JSON.GET returns exact JSON text. Never decode/tonumber an int64 version through Lua's double precision.
local observed = redis.call('JSON.GET', KEYS[1], ARGV[1])
if ARGV[3] == 'CREATE' then
    if observed then return 0 end
else
    if observed ~= '[' .. ARGV[2] .. ']' then return 0 end
end

if ARGV[3] == 'DELETE' then return redis.call('DEL', KEYS[1]) end

if ARGV[5] ~= '' then
    -- Check expiration permissions without changing the key, before any document write.
    local probe = redis.pcall('PEXPIRE', KEYS[1], 'datadam-ttl-permission-check')
    if type(probe) ~= 'table' or probe.err ~= 'ERR value is not an integer or out of range' then
        if type(probe) == 'table' and probe.err then return probe end
        return redis.error_reply('ERR Unable to validate Datadam TTL permissions')
    end
end

local mode = ARGV[3] == 'CREATE' and 'NX' or 'XX'
local result = redis.call('JSON.SET', KEYS[1], '$', ARGV[4], mode)
if not result then return 0 end
-- Version and content are part of this same JSON.SET. UNIQUE failure cannot increment the version separately.
if ARGV[5] ~= '' then redis.call('PEXPIRE', KEYS[1], ARGV[5]) end
return 1
