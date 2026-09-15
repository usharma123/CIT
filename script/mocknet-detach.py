#!/usr/bin/env python3
"""Start a local demo JVM independently of the invoking terminal session."""
import pathlib
import subprocess
import sys

state = pathlib.Path(sys.argv[1])
with (state / 'app.log').open('ab') as log:
    process = subprocess.Popen(
        sys.argv[2:], stdin=subprocess.DEVNULL, stdout=log,
        stderr=subprocess.STDOUT, start_new_session=True,
    )
(state / 'app.pid').write_text(str(process.pid) + '\n')
