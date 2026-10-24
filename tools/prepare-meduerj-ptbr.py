# SPDX-License-Identifier: GPL-3.0-or-later
"""Keep only Kokoro's three Brazilian speakers, without changing shared neural weights.

ONNX ModelProto metadata is rewritten at the protobuf wire level with the standard
library. Graph/tensor fields are copied byte for byte; no Python ML install is needed.
"""
import pathlib
import shutil
import sys


def varint(value):
    out = bytearray()
    while value > 127:
        out.append((value & 127) | 128)
        value >>= 7
    out.append(value)
    return bytes(out)


def read_varint(data, pos):
    value = shift = 0
    while True:
        byte = data[pos]
        pos += 1
        value |= (byte & 127) << shift
        if byte < 128:
            return value, pos
        shift += 7


def fields(data):
    pos = 0
    while pos < len(data):
        start = pos
        tag, pos = read_varint(data, pos)
        wire = tag & 7
        if wire == 2:
            size, pos = read_varint(data, pos)
            payload = data[pos:pos + size]
            pos += size
        elif wire == 0:
            _, pos = read_varint(data, pos)
            payload = b''
        elif wire in (1, 5):
            pos += 8 if wire == 1 else 4
            payload = b''
        else:
            raise ValueError(f'Unsupported protobuf wire type {wire}')
        yield tag >> 3, wire, payload, data[start:pos]


def string_field(number, value):
    value = value.encode()
    return varint(number << 3 | 2) + varint(len(value)) + value


def prepare(source, destination):
    if source == destination:
        raise ValueError('Input model and prepared output directories must be different')
    destination.mkdir(parents=True, exist_ok=True)
    (destination / 'brazilian-voices-v2.txt').unlink(missing_ok=True)
    replacements = {
        'n_speakers': '3', 'speaker_names': 'pf_dora,pm_alex,pm_santa',
        'id2speaker': '0->pf_dora,1->pm_alex,2->pm_santa',
        'speaker2id': 'pf_dora->0,pm_alex->1,pm_santa->2',
        'language': 'pt-BR', 'voice': 'pt-br',
    }
    found = set()
    with (destination / 'model.onnx').open('wb') as output:
        for number, wire, payload, raw in fields((source / 'model.onnx').read_bytes()):
            if number == 14 and wire == 2:
                entry = {n: p.decode() for n, w, p, _ in fields(payload) if w == 2}
                key = entry.get(1)
                if key in replacements:
                    found.add(key)
                    payload = string_field(1, key) + string_field(2, replacements[key])
                    raw = varint(14 << 3 | 2) + varint(len(payload)) + payload
            output.write(raw)
    assert 'n_speakers' in found, 'Unexpected upstream model metadata'
    voices = (source / 'voices.bin').read_bytes()
    assert len(voices) == 54 * 510 * 256 * 4
    stride = len(voices) // 54
    (destination / 'voices.bin').write_bytes(voices[42 * stride:45 * stride])
    shutil.copyfile(source / 'tokens.txt', destination / 'tokens.txt')
    data = destination / 'espeak-ng-data'
    if data.exists():
        # Only this fixed child of the explicit output directory is replaced.
        if data.is_symlink() or data.resolve().parent != destination:
            raise ValueError('Speech data directory must stay inside the output directory')
        shutil.rmtree(data)
    data.mkdir()
    for name in ('phondata', 'phonindex', 'phontab', 'intonations', 'pt_dict'):
        shutil.copyfile(source / 'espeak-ng-data' / name, data / name)
    language = data / 'lang' / 'roa'
    language.mkdir(parents=True)
    shutil.copyfile(source / 'espeak-ng-data/lang/roa/pt-BR', language / 'pt-BR')
    (destination / 'brazilian-voices-v2.txt').write_text('0=pf_dora\n1=pm_alex\n2=pm_santa\n')
    print(f'Brazilian voices only: {len(voices)} -> {3 * stride} bytes; shared weights preserved.')


if __name__ == '__main__':
    prepare(pathlib.Path(sys.argv[1]).resolve(), pathlib.Path(sys.argv[2]).resolve())
