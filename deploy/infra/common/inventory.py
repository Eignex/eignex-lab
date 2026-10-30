"""The capacity inventory a provider adapter publishes for the coordinator (CapacityInventory in Kotlin): built and
checked here, once, whatever the provider."""
import time


def observation(pool, instances, target_size, failures=None, at=None):
    """One pool as a provider sees it: its `instances` as (id, draining) pairs, filled with a booting placeholder for
    each node the provider is still bringing up to `target_size`, and why it could not, when it says."""
    listed = [{'id': instance_id, 'pool': pool, 'draining': draining, 'countedInTarget': not draining}
              for instance_id, draining in instances]
    counted = sum(item['countedInTarget'] for item in listed)
    listed += [{'id': f'booting-{i}', 'pool': pool} for i in range(max(0, target_size - counted))]
    reason = (failures or '')[:500]
    return validate({'observedAt': int((time.time() if at is None else at) * 1000), 'pools': [pool], 'instances': listed,
                     'failures': {pool: reason} if reason else {}})


def merge(observations):
    """Several pools' observations as one inventory, as old as the oldest of them."""
    return validate({'observedAt': min(item['observedAt'] for item in observations),
                     'pools': [pool for item in observations for pool in item['pools']],
                     'instances': [instance for item in observations for instance in item['instances']],
                     'failures': {pool: reason for item in observations for pool, reason in item['failures'].items()}})


def validate(value):
    """`value` when it is an inventory the coordinator accepts: its pools distinct, each instance of one of them and
    listed once; else ValueError."""
    if not isinstance(value.get('observedAt'), int) or not value.get('pools'):
        raise ValueError('inventory needs a time and pools')
    pools = value['pools']
    if len(set(pools)) != len(pools):
        raise ValueError('inventory lists a pool twice')
    seen = set()
    for instance in value['instances']:
        if instance.get('pool') not in pools or not isinstance(instance.get('id'), str):
            raise ValueError('inventory instance outside its pools')
        key = (instance['pool'], instance['id'])
        if key in seen:
            raise ValueError('inventory lists an instance twice')
        seen.add(key)
    if not set(value.get('failures', {})).issubset(pools):
        raise ValueError('inventory failure outside its pools')
    return value
