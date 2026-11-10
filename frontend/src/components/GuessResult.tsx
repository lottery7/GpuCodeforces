import type { GuessResult as Result } from '../api/types';
import '../problem-formats.css';
import { guessLabels } from '../utils';
import { Markdown, TimeValue } from '../ui';

export default function GuessResult({ result }: { result: Result }) {
  const correct = result.choice === result.correctChoice;
  const maximum = Math.max(result.firstTimeMs, result.secondTimeMs);
  return <section className="guess-result" aria-label="Результат ответа">
    <div className="guess-result-title" aria-live="polite"><span className={`guess-result-badge ${correct ? 'correct' : 'incorrect'}`}>{correct ? 'Верно' : 'Не угадали'}</span><h2>{guessLabels[result.correctChoice]}</h2></div>
    {!correct && <p className="muted">Вы выбрали: {guessLabels[result.choice].toLocaleLowerCase()}.</p>}
    <div className="guess-measurements" aria-label="Время вариантов">{[result.firstTimeMs, result.secondTimeMs].map((time, index) => <div className="guess-measurement" key={index}><div><span>Код {index + 1}</span><strong><TimeValue value={time} /> мс</strong></div><div className="guess-time-track" aria-hidden="true"><span style={{ width: `${maximum > 0 ? time / maximum * 100 : 0}%` }} /></div></div>)}</div>
    {result.explanation && <Markdown>{result.explanation}</Markdown>}
  </section>;
}
