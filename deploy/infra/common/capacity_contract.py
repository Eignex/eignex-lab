"""The capacity pools' contract `eignex-lab capacity-export CAPACITY.json` writes (CapacityContract in Kotlin): what each
pool is, as the lab's capacity settings say. An adapter checks its pool against it instead of keeping its own copy."""
import json
from pathlib import Path

SCHEMA_VERSION = 1


def load(path):
    """The contract at `path`, its pools by id."""
    value = json.loads(Path(path).read_text())
    if value.get('schemaVersion') != SCHEMA_VERSION:
        raise ValueError('unsupported capacity contract')
    return {pool['id']: pool for pool in value['pools']}


def check(path, pool, **expected):
    """The contract's pool `pool`, which must exist and agree with every `expected` field, such as maxNodes=4; else
    ValueError naming what differs. Without a contract (`path` None), nothing is checked."""
    if path is None:
        return None
    pools = load(path)
    if pool not in pools:
        raise ValueError(f'capacity pool {pool} is not in the contract')
    different = {key: (value, pools[pool].get(key)) for key, value in expected.items() if pools[pool].get(key) != value}
    if different:
        raise ValueError(f'capacity pool {pool} differs from the contract: ' +
                         ', '.join(f'{key} {mine} here, {theirs} there' for key, (mine, theirs) in different.items()))
    return pools[pool]
