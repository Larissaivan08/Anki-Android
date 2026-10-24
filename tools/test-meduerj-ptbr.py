# SPDX-License-Identifier: GPL-3.0-or-later
"""Check the real prepared model against the pinned upstream files."""
import hashlib
import pathlib
import runpy
import sys

helpers = runpy.run_path(str(pathlib.Path(__file__).with_name('prepare-meduerj-ptbr.py')))
fields = helpers['fields']
source, prepared = map(pathlib.Path, sys.argv[1:3])


def graph_digest(path):
    for number, _, payload, _ in fields(path.read_bytes()):
        if number == 7:
            return hashlib.sha256(payload).hexdigest()
    raise AssertionError('Missing neural graph')


assert graph_digest(source / 'model.onnx') == graph_digest(prepared / 'model.onnx'), 'Neural weights/graph changed'
original = (source / 'voices.bin').read_bytes()
stride = 510 * 256 * 4
assert (prepared / 'voices.bin').read_bytes() == original[42 * stride:45 * stride]
metadata = {}
for number, wire, payload, _ in fields((prepared / 'model.onnx').read_bytes()):
    if number == 14 and wire == 2:
        entry = {n: p.decode() for n, w, p, _ in fields(payload) if w == 2}
        metadata[entry[1]] = entry[2]
assert metadata['n_speakers'] == '3'
assert metadata['speaker_names'] == 'pf_dora,pm_alex,pm_santa'
assert metadata['language'] == 'pt-BR'
assert sorted(p.relative_to(prepared / 'espeak-ng-data').as_posix()
              for p in (prepared / 'espeak-ng-data').rglob('*') if p.is_file()) == [
    'intonations', 'lang/roa/pt-BR', 'phondata', 'phonindex', 'phontab', 'pt_dict',
]
print('PASS: shared neural graph unchanged; exactly three original Brazilian voices; only pt-BR language data.')
