-- Datadam checks ACL before parsing PEXPIRE's value. Probe without changing the key:
-- a denied expiry must fail BEFORE JSON.SET, since scripts do not roll back writes.
-- Fail closed if the server does not give the documented non-integer error.
local probe = redis.pcall('PEXPIRE', KEYS[1], 'datadam-ttl-permission-check')
if type(probe) ~= 'table' or probe.err ~= 'ERR value is not an integer or out of range' then
    if type(probe) == 'table' and probe.err then return probe end
    return redis.error_reply('ERR Unable to validate Datadam TTL permissions')
end

-- ARGV[2] is validated as positive, whole milliseconds by the Java mapping layer.
-- Keep it as text: Lua numbers cannot represent every signed 64-bit TTL exactly.
-- A JSON.SET error (e.g. UNIQUE or WRONGTYPE) stops the script before expiration changes.
local result
if ARGV[3] == 'NX' then
    result = redis.call('JSON.SET', KEYS[1], '$', ARGV[1], 'NX')
else
    result = redis.call('JSON.SET', KEYS[1], '$', ARGV[1])
end
-- An ID collision must not change the existing document's expiry.
if not result then return false end
redis.call('PEXPIRE', KEYS[1], ARGV[2])
return result
