import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';

function useRuntime(enabled = true) {
  return useQuery({ queryKey: ['runtime'], queryFn: ({ signal }) => api.getRuntime(signal), staleTime: 300000, enabled });
}

export function SyntheticResultsNotice({ synthetic }: { synthetic?: boolean }) {
  const runtime = useRuntime(synthetic === true);
  return synthetic && runtime.data?.executionMode !== 'mock' ? <span className="small muted">Тестовые результаты</span> : null;
}

export default function RuntimeNotice() {
  const runtime = useRuntime();
  return runtime.data?.executionMode === 'mock' ? <div className="notice runtime-notice" role="status">Локальный стенд: код не выполняется, результаты тестовые.</div> : null;
}
