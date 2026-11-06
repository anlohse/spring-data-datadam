-- ARGV: mode, version field (empty if unversioned), expected version, TTL milliseconds, field/value pairs.
-- HREPLACE is a Datadam extension: final field set and UNIQUE validation are one KeyTxn.
local count = redis.call('HLEN', KEYS[1]) -- also refuses a key of another storage type
local mode = ARGV[1]
if mode == 'CREATE' and count ~= 0 then return 0 end
if mode == 'UPDATE' or mode == 'DELETE' then
    if count == 0 or redis.call('HGET', KEYS[1], ARGV[2]) ~= ARGV[3] then return 0 end
end
if mode == 'DELETE' or mode == 'DELETE_ANY' then return redis.call('DEL', KEYS[1]) end
if ARGV[4] ~= '' then
    local probe = redis.pcall('PEXPIRE', KEYS[1], 'datadam-ttl-permission-check')
    if type(probe) ~= 'table' or probe.err ~= 'ERR value is not an integer or out of range' then
        if type(probe) == 'table' and probe.err then return probe end
        return redis.error_reply('ERR Unable to validate Datadam TTL permissions')
    end
end
redis.call('HREPLACE', KEYS[1], unpack(ARGV, 5))
if ARGV[4] ~= '' then redis.call('PEXPIRE', KEYS[1], ARGV[4]) end
return 1
