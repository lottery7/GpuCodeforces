import type { GuessResult as Result } from '../api/types';
import '../problem-formats.css';
import { guessLabels } from '../utils';
import { Markdown, ScoreValue } from '../ui';
import { SyntheticResultsNotice } from './RuntimeNotice';
import { t } from '../i18n';

export default function GuessResult({ result }: { result: Result }) {
  const correct = result.choice === result.correctChoice;
  const maximum = Math.max(result.firstScore.value, result.secondScore.value);
  return <section className="guess-result" aria-label={t('Результат ответа')}>
    <div className="guess-result-title" aria-live="polite"><span className={`guess-result-badge ${correct ? 'correct' : 'incorrect'}`}>{correct ? t('Верно') : t('Не угадали')}</span><h2>{guessLabels[result.correctChoice]}</h2></div>
    <SyntheticResultsNotice synthetic={result.synthetic} />
    {!correct && <p className="muted">{t('Вы выбрали:')} {guessLabels[result.choice].toLocaleLowerCase()}.</p>}
    <div className="guess-measurements" aria-label={t('Результаты вариантов')}>{[result.firstScore, result.secondScore].map((score, index) => <div className="guess-measurement" key={index}><div><span>{t('Код')} {index + 1}</span><strong><ScoreValue score={score} /></strong></div><div className="guess-time-track" aria-hidden="true"><span style={{ width: `${maximum > 0 ? score.value / maximum * 100 : 0}%` }} /></div></div>)}</div>
    {result.explanation && <Markdown>{result.explanation}</Markdown>}
  </section>;
}
