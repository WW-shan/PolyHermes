-- Move the seeded PublicNode entry to the provider's current Polygon Bor RPC hostname.
-- Only touch the known application default; leave user-entered RPC endpoints unchanged.
UPDATE rpc_node_config
SET http_url = 'https://polygon-bor-rpc.publicnode.com',
    ws_url = 'wss://polygon-bor-rpc.publicnode.com',
    last_check_time = NULL,
    last_check_status = 'UNKNOWN',
    response_time_ms = NULL,
    updated_at = UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000
WHERE provider_type = 'PUBLIC'
  AND name = 'PublicNode (Default)'
  AND http_url IN (
      'https://polygon.publicnode.com',
      'https://polygon.publicnode.com/'
  );
