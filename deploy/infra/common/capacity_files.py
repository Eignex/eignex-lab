"""Private atomic provider-neutral inventory publication."""
import json
import os
from pathlib import Path
import tempfile


def write_atomic(path, value):
    path = Path(path); path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(dir=path.parent, prefix=".inventory-")
    try:
        with os.fdopen(descriptor, "w") as output:
            json.dump(value, output); output.flush(); os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)

