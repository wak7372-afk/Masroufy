"""Offline, separately authored synthetic header holdout; never an OCR benchmark."""
import argparse
import json
import math
import re
from pathlib import Path
from train import features, ROOT


def load_model(path):
    return json.loads(''.join(re.findall(r'"""(.*?)"""', path.read_text(encoding='utf-8'), re.S)))


def predict(model, text, coverage=.75, margin=6., probability=.995):
    if not 4 <= len(text) <= 80 or any(c.isdigit() for c in text):
        return None
    vocabulary = {value: i for i, value in enumerate(model['features'])}
    tokens = features(text)
    positions = [vocabulary[value] for value in tokens if value in vocabulary]
    if not tokens or len(positions) / len(tokens) < coverage:
        return None
    scores = sorted(((sum(weights[i] for i in positions), role) for role, weights in model['weights'].items()), reverse=True)
    top, role = scores[0]
    posterior = 1 / sum(math.exp(value - top) for value, _ in scores)
    return role if role != 'OTHER' and posterior >= probability and top - scores[1][0] >= margin else None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    data = json.loads(Path(__file__).with_name('header_holdout.json').read_text(encoding='utf-8'))
    training = json.loads(Path(__file__).with_name('header_examples.json').read_text(encoding='utf-8'))
    train_values = {v.casefold() for values in training.values() for v in values}
    assert not any(value.casefold() in train_values for values in data.values() for value in values), 'Holdout overlaps training'
    model = load_model(ROOT / 'shared/src/commonMain/kotlin/om/masroofi/core/LocalColumnWeights.kt')
    cases = [{'text': text, 'expected': role, 'prediction': predict(model, text)} for role, values in data.items() for text in values]
    accepted = [case for case in cases if case['prediction'] is not None]
    wrong = [case for case in accepted if case['prediction'] != case['expected']]
    result = {'dataset': 'authored synthetic header holdout; not bank PDFs or images', 'total': len(cases), 'accepted': len(accepted), 'correct_accepted': len(accepted)-len(wrong), 'wrong_accepted': len(wrong), 'abstained': len(cases)-len(accepted), 'cases': cases}
    if args.output: args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({k:v for k,v in result.items() if k!='cases'}))
    if wrong: print(json.dumps(wrong, ensure_ascii=False))


if __name__ == '__main__': main()
