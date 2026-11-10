import type { GuessResult as Result } from '../api/types';
import { guessLabels } from '../utils';
import { Markdown, TimeValue } from '../ui';

export default function GuessResult({ result }: { result: Result }) {
  return <section className="guess-result" aria-label="Результат ответа">
    <p className={result.choice === result.correctChoice ? 'success' : 'error'} role="status">{result.choice === result.correctChoice ? 'Верно!' : 'Неверно.'}</p>
    <p>Ваш ответ: {guessLabels[result.choice]}. Правильный ответ: {guessLabels[result.correctChoice]}.</p>
    <p>Код 1: <TimeValue value={result.firstTimeMs} /> мс. Код 2: <TimeValue value={result.secondTimeMs} /> мс.</p>
    {result.explanation && <Markdown>{result.explanation}</Markdown>}
  </section>;
}
