(function () {
  const storageKey = 'gpu-forces-prototype-v3';
  const sessionKey = 'gpu-forces-session-v1';
  const version = 3;
  const copy = value => value == null ? value : JSON.parse(JSON.stringify(value));
  const nowIso = () => new Date().toISOString();
  const codeFor = signature => `#include <cuda_runtime.h>\n\n${signature} {\n    \n}\n`;
  const gpuProfiles = [
    {id:'a100',label:'NVIDIA A100 80 GB',profileId:'gpu-a100-v1',referenceScale:1},
    {id:'h100',label:'NVIDIA H100 80 GB',profileId:'gpu-h100-v1',referenceScale:0.7}
  ];
  const catalog = [
    { id:'vector-addition', code:'001', title:'Сложение векторов', difficulty:'easy', tags:['Память','Массивы'], statement:'Вычислите поэлементную сумму двух векторов A и B из N чисел float32. Запишите результат в вектор C на GPU.', formula:'C[i] = A[i] + B[i]', example:'N = 4\nA = [1, 2, 3, 4]\nB = [5, 6, 7, 8]\nC = [6, 8, 10, 12]', signature:'void solve(const float* A, const float* B, float* C, int N)', caseLabel:'N = 2^24', referenceMs:0.065 },
    { id:'matrix-multiplication', code:'002', title:'Умножение матриц', difficulty:'medium', tags:['Матрицы','Память'], statement:'Вычислите произведение матриц A размера M x K и B размера K x N. Матрицы содержат float32 и хранятся построчно в памяти GPU.', formula:'C[i, j] = sum(A[i, k] * B[k, j])', example:'M = N = K = 2\nA = [[1, 2], [3, 4]]\nB = [[1, 0], [0, 1]]\nC = [[1, 2], [3, 4]]', signature:'void solve(const float* A, const float* B, float* C, int M, int N, int K)', caseLabel:'M = N = K = 1024', referenceMs:3.9 },
    { id:'matrix-transpose', code:'003', title:'Транспонирование матрицы', difficulty:'easy', tags:['Матрицы','Память'], statement:'Транспонируйте матрицу A размера M x N, записанную построчно в памяти GPU. Результат B должен иметь размер N x M.', formula:'B[j, i] = A[i, j]', example:'M = 2, N = 3\nA = [[1, 2, 3], [4, 5, 6]]\nB = [[1, 4], [2, 5], [3, 6]]', signature:'void solve(const float* A, float* B, int M, int N)', caseLabel:'M = N = 4096', referenceMs:0.0475 },
    { id:'reduction', code:'004', title:'Параллельная редукция', difficulty:'medium', tags:['Редукции','Синхронизация'], statement:'Вычислите сумму N значений float32 из массива A в памяти GPU. Запишите одно итоговое значение в output.', formula:'output[0] = sum(A[i])', example:'N = 5\nA = [1, 2, 3, 4, 5]\noutput = [15]', signature:'void solve(const float* A, float* output, int N)', caseLabel:'N = 2^24', referenceMs:0.15 },
    { id:'softmax', code:'005', title:'Softmax', difficulty:'medium', tags:['Редукции','Массивы'], statement:'Вычислите численно устойчивый softmax для N значений float32. Вход и выход находятся в памяти GPU.', formula:'Y[i] = exp(X[i] - max(X)) / sum(exp(X - max(X)))', example:'N = 3\nX = [0, 0, 0]\nY = [0.333333, 0.333333, 0.333333]', signature:'void solve(const float* X, float* Y, int N)', caseLabel:'N = 2^20', referenceMs:0.1 },
    { id:'prefix-sum', code:'006', title:'Префиксная сумма', difficulty:'hard', tags:['Синхронизация','Массивы'], statement:'Для массива X вычислите включающие префиксные суммы. Каждый элемент Y содержит сумму от X[0] до X[i] включительно.', formula:'Y[i] = X[0] + ... + X[i]', example:'N = 5\nX = [1, 2, 3, 4, 5]\nY = [1, 3, 6, 10, 15]', signature:'void solve(const float* X, float* Y, int N)', caseLabel:'N = 2^22', referenceMs:0.3 },
    { id:'relu', code:'007', title:'Активация ReLU', difficulty:'easy', tags:['Массивы','Память'], statement:'Примените ReLU к каждому элементу массива float32 на GPU. Отрицательные значения замените нулем.', formula:'Y[i] = max(0, X[i])', example:'N = 4\nX = [-2, 0, 3, -1]\nY = [0, 0, 3, 0]', signature:'void solve(const float* X, float* Y, int N)', caseLabel:'N = 2^24', referenceMs:0.065 },
    { id:'histogram', code:'008', title:'Гистограмма', difficulty:'hard', tags:['Синхронизация','Память'], statement:'Постройте гистограмму массива X из N целых чисел от 0 до 255. Запишите 256 счетчиков в output в памяти GPU.', formula:'output[k] = count(X[i] == k)', example:'N = 5\nX = [0, 1, 1, 3, 0]\noutput = [2, 2, 0, 1, 0, ...]', signature:'void solve(const int* X, int* output, int N)', caseLabel:'N = 2^24, 256 корзин', referenceMs:0.4 }
  ].map(item => ({
    ...item,
    visibility:'public',
    starter:codeFor(item.signature),
    format:'gpu-function',
    contract:'Реализуйте solve. Указатели ссылаются на память GPU. Результат записывается в выходной массив. Используется поток CUDA по умолчанию.',
    limits:{timeSeconds:2,memoryMb:1024,input:item.id==='matrix-multiplication'?'1 <= M, N, K <= 4096':item.id==='matrix-transpose'?'1 <= M, N <= 4096':item.id==='histogram'?'1 <= N <= 2^24; 0 <= X[i] <= 255':'1 <= N <= 2^24'},
    tolerance:item.id === 'histogram' ? 'Точное совпадение целых значений' : 'abs(actual - expected) <= 1e-5 + 1e-5 * abs(expected)',
    benchmark:{id:`${item.id}-fixed-v1`,profileId:'gpu-a100-v1',testVersion:'demo-v1',gpu:'NVIDIA A100 80 GB',caseLabel:item.caseLabel,warmups:10,runs:50,unit:'ms',method:'Медиана времени ядра',compiler:'C++17 / CUDA',referenceMs:item.referenceMs}
  }));
  const acceptedCode = {
    'vector-addition':'#include <cuda_runtime.h>\n\n__global__ void add(const float* A, const float* B, float* C, int N) {\n    int i = blockIdx.x * blockDim.x + threadIdx.x;\n    if (i < N) C[i] = A[i] + B[i];\n}\n\nvoid solve(const float* A, const float* B, float* C, int N) {\n    add<<<(N + 255) / 256, 256>>>(A, B, C, N);\n}\n',
    'matrix-transpose':'#include <cuda_runtime.h>\n\n__global__ void transpose(const float* A, float* B, int M, int N) {\n    int i = blockIdx.x * blockDim.x + threadIdx.x;\n    if (i < M * N) B[(i % N) * M + i / N] = A[i];\n}\n\nvoid solve(const float* A, float* B, int M, int N) {\n    transpose<<<(M * N + 255) / 256, 256>>>(A, B, M, N);\n}\n',
    relu:'#include <cuda_runtime.h>\n\n__global__ void relu(const float* X, float* Y, int N) {\n    int i = blockIdx.x * blockDim.x + threadIdx.x;\n    if (i < N) Y[i] = X[i] > 0.0f ? X[i] : 0.0f;\n}\n\nvoid solve(const float* X, float* Y, int N) {\n    relu<<<(N + 255) / 256, 256>>>(X, Y, N);\n}\n',
    reduction:'#include <cuda_runtime.h>\n\n__global__ void reduce(const float* A, float* output, int N) {\n    if (blockIdx.x == 0 && threadIdx.x == 0) {\n        float sum = 0.0f;\n        for (int i = 0; i < N; ++i) sum += A[i];\n        output[0] = sum;\n    }\n}\n\nvoid solve(const float* A, float* output, int N) {\n    reduce<<<1, 1>>>(A, output, N);\n}\n'
  };
  const baseProblem = id => catalog.find(item => item.id === id) || null;
  function privatePreparedProblem(code='009') {
    const base=baseProblem('reduction');
    const signature='void solve(const float* X, const int* offsets, float* Y, int N, int K)';
    return {...copy(base),id:'segmented-reduction',code,title:'Сегментированная редукция',ownerId:'alex_cuda',visibility:'private',tags:['Редукции','Синхронизация'],statement:'Вычислите сумму значений каждого из K сегментов массива X. Границы сегментов заданы массивом offsets длины K + 1. Запишите K сумм в массив Y.',formula:'Y[k] = sum(X[i]), offsets[k] <= i < offsets[k + 1]',example:'N = 5, K = 2\nX = [1, 2, 3, 4, 5]\noffsets = [0, 2, 5]\nY = [3, 12]',signature,starter:codeFor(signature),contract:'Реализуйте solve. Массивы X, offsets и Y находятся в памяти GPU. X и Y содержат float32, offsets - int32. Используется поток CUDA по умолчанию.',limits:{...copy(base.limits),input:'1 <= K <= N <= 2^24; offsets[0] = 0; offsets[K] = N; offsets[k] < offsets[k + 1]'},benchmark:{...copy(base.benchmark),id:'segmented-reduction-fixed-v1',caseLabel:'N = 2^24, K = 1024',referenceMs:0.2}};
  }
  function seededCode(problemId) {
    if (acceptedCode[problemId]) return acceptedCode[problemId];
    if (problemId === 'matrix-multiplication') return '#include <cuda_runtime.h>\n\n__global__ void multiply(const float* A, const float* B, float* C, int M, int N, int K) {\n    int index = blockIdx.x * blockDim.x + threadIdx.x;\n    if (index >= M * N) return;\n    int row = index / N;\n    int col = index % N;\n    float sum = 0.0f;\n    for (int k = 0; k < K; ++k) sum += A[row * K + k] * B[k * N + col];\n    C[index] = sum;\n}\n\nvoid solve(const float* A, const float* B, float* C, int M, int N, int K) {\n    multiply<<<(M * N + 255) / 256, 256>>>(A, B, C, M, N, K);\n}\n';
    return codeFor(baseProblem(problemId).signature);
  }
  function makeSeed() {
    const clock = Date.now();
    const date = offset => new Date(clock + offset).toISOString();
    const day = 86400000;
    const hour = 3600000;
    const users = [
      {id:'kernel_panic',handle:'kernel_panic',email:'kernel@example.com',role:'participant',rating:2348,ratingDelta:86,ratingContestId:'weekly-11'},
      {id:'warp_speed',handle:'warp_speed',email:'warp@example.com',role:'participant',rating:2261,ratingDelta:42,ratingContestId:'weekly-11'},
      {id:'shared_memory',handle:'shared_memory',email:'shared@example.com',role:'participant',rating:2187,ratingDelta:65,ratingContestId:'weekly-11'},
      {id:'grid_coalesce',handle:'grid_coalesce',email:'grid@example.com',role:'participant',rating:2240,ratingDelta:51,ratingContestId:'weekly-11'},
      {id:'warp_guru',handle:'warp_guru',email:'warp.guru@example.com',role:'participant',rating:2195,ratingDelta:33,ratingContestId:'weekly-11'},
      {id:'cache_line',handle:'cache_line',email:'cache@example.com',role:'participant',rating:2110,ratingDelta:58,ratingContestId:'weekly-11'},
      {id:'unroll_me',handle:'unroll_me',email:'unroll@example.com',role:'participant',rating:2055,ratingDelta:44,ratingContestId:'weekly-11'},
      {id:'occupancy_king',handle:'occupancy_king',email:'occupancy@example.com',role:'participant',rating:1980,ratingDelta:29,ratingContestId:'weekly-11'},
      {id:'sm_hero',handle:'sm_hero',email:'sm@example.com',role:'participant',rating:1925,ratingDelta:40,ratingContestId:'weekly-11'},
      {id:'tensor_tornado',handle:'tensor_tornado',email:'tensor@example.com',role:'participant',rating:1876,ratingDelta:22,ratingContestId:'weekly-11'},
      {id:'alex_cuda',handle:'alex_cuda',email:'alex@example.com',role:'participant',rating:1842,ratingDelta:124,ratingContestId:'weekly-11'},
      {id:'parallel_mind',handle:'parallel_mind',email:'parallel@example.com',role:'participant',rating:1786,ratingDelta:-18,ratingContestId:'weekly-11'},
      {id:'thread_block',handle:'thread_block',email:'thread@example.com',role:'participant',rating:1743,ratingDelta:37,ratingContestId:'weekly-11'},
      {id:'block_dim',handle:'block_dim',email:'block@example.com',role:'participant',rating:1690,ratingDelta:31,ratingContestId:'weekly-11'},
      {id:'stream_merger',handle:'stream_merger',email:'stream@example.com',role:'participant',rating:1612,ratingDelta:18,ratingContestId:'weekly-11'},
      {id:'author_cuda',handle:'author_cuda',email:'author@example.com',role:'participant',rating:null,ratingDelta:null,ratingContestId:null},
      {id:'new_cuda',handle:'new_cuda',email:'new@example.com',role:'participant',rating:null,ratingDelta:null,ratingContestId:null}
    ];
    const problemIds = ['vector-addition','matrix-multiplication','reduction'];
    const contest = (id,title,start,end) => ({id,title,ownerId:'author_cuda',authorIds:['author_cuda'],gpuId:'a100',startAt:date(start),endAt:date(end),problemIds:problemIds.slice(),registrationOpen:end>0,benchmarks:Object.fromEntries(problemIds.map(id=>[id,copy(baseProblem(id).benchmark)])),problemSnapshots:Object.fromEntries(problemIds.map(id=>[id,copy(baseProblem(id))]))});
    const contests = [contest('weekly-12','Weekly Contest #12',-hour,hour),contest('weekly-13','Weekly Contest #13',7*day,7*day+2*hour),contest('weekly-11','Weekly Contest #11',-2*day,-2*day+2*hour),contest('weekly-10','Weekly Contest #10',-9*day,-9*day+2*hour)];
    const records = [];
    const add = (id,userId,problemId,timeMs,contestId=null,createdAt=date(-hour)) => {
      const problem = baseProblem(problemId);
      records.push({id,userId,problemId,contestId,status:'accepted',timeMs,createdAt,code:seededCode(problemId),format:problem.format,templateId:problem.format,profileId:problem.benchmark.profileId,testVersion:problem.benchmark.testVersion,benchmarkId:problem.benchmark.id,benchmark:copy(problem.benchmark),limits:copy(problem.limits),tolerance:problem.tolerance,passedTests:24,totalTests:24,diagnostic:null});
    };
    add(1042,'alex_cuda','vector-addition',0.052,null,date(-20*60000));
    add(1038,'alex_cuda','vector-addition',null,null,date(-40*60000));
    Object.assign(records[records.length-1],{status:'wrong',passedTests:6,code:acceptedCode['vector-addition'].replace('i < N','i < N - 1'),diagnostic:{test:7,line:5,message:'Последний элемент выходного массива не записан.',absoluteError:'0.03125',relativeError:'0.018'}});
    add(1029,'alex_cuda','reduction',null,null,date(-2*hour));
    Object.assign(records[records.length-1],{status:'compile',passedTests:0,code:'#include <cuda_runtime.h>\n\nvoid solve(const float* A, float* output, int N) {\n    int sum = idx;\n}\n',diagnostic:{line:4,message:'solution.cu(4): error: identifier "idx" is undefined'}});
    add(1014,'alex_cuda','matrix-transpose',0.038,null,date(-day));
    add(1006,'alex_cuda','relu',0.021,null,date(-day-hour));
    add(1005,'alex_cuda','relu',0.018,null,date(-3*day));
    Object.assign(records[records.length-1],{profileId:'gpu-a100-v0',testVersion:'demo-v0',benchmark:{...copy(baseProblem('relu').benchmark),profileId:'gpu-a100-v0',testVersion:'demo-v0'}});
    let id = 1100;
    const rivals = [['kernel_panic',0.63],['warp_speed',0.68],['grid_coalesce',0.65],['warp_guru',0.69],['shared_memory',0.71],['cache_line',0.73],['unroll_me',0.78],['occupancy_king',0.83],['sm_hero',0.86],['tensor_tornado',0.89],['parallel_mind',0.9],['thread_block',1.05],['block_dim',1.12],['stream_merger',1.2]];
    rivals.forEach(([userId,factor],personIndex)=>{
      ['vector-addition','matrix-transpose','relu','reduction','matrix-multiplication'].forEach((problemId,index)=>add(id++,userId,problemId,Number((baseProblem(problemId).benchmark.referenceMs*factor).toFixed(4)),null,date(-day-index*60000-personIndex*1000)));
    });
    contests.filter(item=>item.id!=='weekly-13').forEach((item,contestIndex)=>{
      const submitted = new Date(Date.parse(item.startAt)+30*60000).toISOString();
      rivals.slice(0,3).forEach(([userId,factor])=>problemIds.forEach(problemId=>add(id++,userId,problemId,Number((baseProblem(problemId).benchmark.referenceMs*factor).toFixed(4)),item.id,submitted)));
      add(id++,'alex_cuda','vector-addition',contestIndex===0?0.052:0.054,item.id,submitted);
      add(id++,'alex_cuda','matrix-multiplication',contestIndex===0?3.12:3.25,item.id,submitted);
      add(id++,'parallel_mind','vector-addition',0.057,item.id,submitted);
    });
    const registrations = Array.from(new Set(records.filter(item=>item.contestId).map(item=>`${item.contestId}|${item.userId}`))).map(key=>{const [contestId,userId]=key.split('|');return {contestId,userId};});
    const preparedProblems = [privatePreparedProblem()];
    records.forEach(item=>{item.benchmarkSnapshot=copy(item.benchmark);});
    return {version,createdAt:nowIso(),sessionId:null,returnTo:null,users,contests,submissions:records,registrations,drafts:{},preparedProblems,nextSubmissionId:id,nextContestId:1,ratingEvents:users.filter(item=>item.rating!==null).flatMap(item=>[{userId:item.id,contestId:'weekly-11',ratingDelta:item.ratingDelta,ratingAfter:item.rating},{userId:item.id,contestId:'weekly-10',ratingDelta:37,ratingAfter:item.rating-item.ratingDelta}])};
  }
  let storageError = '';
  let state;
  try {
    const stored = JSON.parse(localStorage.getItem(storageKey) || 'null');
    state = stored && stored.version===version && Array.isArray(stored.users) && Array.isArray(stored.submissions) && Array.isArray(stored.contests) && (Array.isArray(stored.preparedProblems) || Array.isArray(stored.authorTasks)) && stored.drafts ? stored : makeSeed();
  } catch (_) {
    state = makeSeed();
  }
  try {
    const sessionId = sessionStorage.getItem(sessionKey);
    state.sessionId = state.users.some(user=>user.id===sessionId) ? sessionId : null;
  } catch (_) {
    state.sessionId = null;
  }
  if(!Array.isArray(state.preparedProblems))state.preparedProblems=[];
  const legacyTasks=Array.isArray(state.authorTasks)?state.authorTasks:[];
  if(legacyTasks.length){
    const archive=new Map((Array.isArray(state.legacyTaskArchive)?state.legacyTaskArchive:[]).map(task=>[task.id,task]));
    legacyTasks.forEach(task=>archive.set(task.id,copy(task)));
    state.legacyTaskArchive=Array.from(archive.values());
  }
  legacyTasks.forEach(task=>{
    if(!task.publishedData||state.preparedProblems.some(problem=>problem.id===task.publishedData.id))return;
    const problem=copy(task.publishedData);
    if(!['private','public'].includes(problem.visibility))problem.visibility=task.status==='published'&&['private','public'].includes(task.visibility)?task.visibility:'public';
    state.preparedProblems.push({...problem,ownerId:task.ownerId});
  });
  if(!state.preparedProblems.some(problem=>problem.id==='segmented-reduction')){
    const nextCode=Math.max(8,...state.preparedProblems.map(problem=>Number(problem.code)||0))+1;
    state.preparedProblems.push(privatePreparedProblem(String(nextCode).padStart(3,'0')));
  }
  delete state.authorTasks;
  delete state.nextTaskId;
  state.users.forEach(user=>{if(user.role==='author')user.role='participant';});
  state.preparedProblems.forEach(problem=>{
    if(!['private','public'].includes(problem.visibility))problem.visibility='public';
    if(!problem.limits?.input)problem.limits={...copy(problem.limits||catalog[0].limits),input:baseProblem(problem.id)?.limits.input||catalog[0].limits.input};
  });
  state.submissions.forEach(item=>{if(!item.limits?.input)item.limits={...copy(item.limits||catalog[0].limits),input:baseProblem(item.problemId)?.limits.input||catalog[0].limits.input};});
  if(!Number.isInteger(state.nextContestId)||state.nextContestId<1)state.nextContestId=1;
  state.contests.forEach(item=>{
    if(!item.ownerId&&['weekly-10','weekly-11','weekly-12','weekly-13'].includes(item.id))item.ownerId='author_cuda';
    const authors=Array.isArray(item.authorIds)?item.authorIds.filter(id=>state.users.some(user=>user.id===id)):[];
    if(!item.ownerId&&authors.length)item.ownerId=authors[0];
    item.authorIds=Array.from(new Set(authors.length?authors:state.users.some(user=>user.id===item.ownerId)?[item.ownerId]:[]));
    if(!item.gpuId){
      const benchmark=Object.values(item.benchmarks||{})[0];
      item.gpuId=gpuProfiles.find(profile=>profile.profileId===benchmark?.profileId)?.id||'a100';
    }
    if(!item.problemSnapshots)item.problemSnapshots={};
    item.problemIds.forEach(id=>{
      if(!item.problemSnapshots[id]){
        const problem=baseProblem(id);
        if(problem)item.problemSnapshots[id]={...copy(problem),benchmark:copy(item.benchmarks[id]||problem.benchmark)};
      }
      const snapshot=item.problemSnapshots[id];
      if(snapshot&&!snapshot.limits?.input)snapshot.limits={...copy(snapshot.limits||catalog[0].limits),input:baseProblem(id)?.limits.input||catalog[0].limits.input};
    });
  });
  function persist() {
    try {localStorage.setItem(storageKey,JSON.stringify({...state,sessionId:null}));storageError='';}
    catch (_) {storageError='Не удалось сохранить данные в браузере. До закрытия вкладки они останутся доступны.';}
    try {
      if(state.sessionId)sessionStorage.setItem(sessionKey,state.sessionId);
      else sessionStorage.removeItem(sessionKey);
    } catch (_) {}
  }
  const findPerson = id => state.users.find(item=>item.id===id) || null;
  const current = () => findPerson(state.sessionId);
  function preparedCatalog() {
    const items = catalog.map(copy);
    state.preparedProblems.forEach(problem=>{
      const index=items.findIndex(item=>item.id===problem.id);
      if(index>=0)items[index]=copy(problem);
      else items.push(copy(problem));
    });
    return items;
  }
  const allProblems = () => preparedCatalog().filter(problem=>problem.visibility==='public');
  function contestProblems() {
    return preparedCatalog().filter(problem=>problem.visibility==='public'||!!current()&&problem.ownerId===state.sessionId);
  }
  const findProblem = (id,contestId=null) => contestId ? findContest(contestId)?.problemSnapshots[id]||null : allProblems().find(item=>item.id===id)||null;
  const findContest = id => state.contests.find(item=>item.id===id) || null;
  function phase(id) {
    const item=findContest(id);
    if (!item) return null;
    const clock=Date.now();
    return clock<Date.parse(item.startAt)?'upcoming':clock<Date.parse(item.endAt)?'live':'finished';
  }
  function canEditContest(id) {
    return !!current()&&isContestAuthor(id)&&phase(id)==='upcoming';
  }
  function isContestAuthor(id,userId=state.sessionId) {
    return !!userId&&!!findContest(id)?.authorIds.includes(userId);
  }
  function saveContest(id,data={}) {
    if(!current())return {ok:false,error:'Войдите, чтобы создавать контесты.'};
    const old=id?findContest(id):null;
    if(id&&!old)return {ok:false,error:'Контест не найден.'};
    if(old&&!isContestAuthor(id))return {ok:false,error:'Изменять контест могут только его авторы.'};
    if(old&&phase(id)!=='upcoming')return {ok:false,error:'После начала контест нельзя изменить.'};
    const requestedAuthors=data.authorIds===undefined?old?.authorIds??[state.sessionId]:data.authorIds;
    if(!Array.isArray(requestedAuthors)||requestedAuthors.some(authorId=>typeof authorId!=='string'||!findPerson(authorId)))return {ok:false,error:'Выберите авторов из списка пользователей.'};
    if(!requestedAuthors.length)return {ok:false,error:'У контеста должен быть хотя бы один автор.'};
    const authorIds=Array.from(new Set(requestedAuthors));
    const title=String(data.title??old?.title??'').trim();
    if(!title)return {ok:false,error:'Укажите название контеста.'};
    if(title.length>100)return {ok:false,error:'Название должно быть не длиннее 100 символов.'};
    const startAt=Date.parse(data.startAt??old?.startAt??'');
    if(!Number.isFinite(startAt)||startAt<=Date.now())return {ok:false,error:'Начало контеста должно быть в будущем.'};
    const durationMinutes=Number(data.durationMinutes??(old?(Date.parse(old.endAt)-Date.parse(old.startAt))/60000:0));
    const endAt=startAt+durationMinutes*60000;
    if(!Number.isInteger(durationMinutes)||durationMinutes<1||!Number.isFinite(new Date(endAt).getTime()))return {ok:false,error:'Укажите длительность в целых минутах, не меньше 1.'};
    const gpuId=String(data.gpuId??old?.gpuId??'');
    const gpu=gpuProfiles.find(profile=>profile.id===gpuId);
    if(!gpu)return {ok:false,error:'Выберите GPU для контеста.'};
    const selected=data.problemIds??old?.problemIds;
    if(!Array.isArray(selected)||!selected.length)return {ok:false,error:'Добавьте хотя бы одну задачу.'};
    const problemIds=Array.from(new Set(selected.map(String)));
    const available=contestProblems();
    const problems=problemIds.map(problemId=>available.find(problem=>problem.id===problemId)||(old?.problemIds.includes(problemId)?old.problemSnapshots?.[problemId]:null));
    if(problems.some(problem=>!problem))return {ok:false,error:'Одна из выбранных задач недоступна.'};
    let contestId=old?.id;
    if(!contestId){
      do {contestId=`contest-${state.nextContestId++}`;} while(findContest(contestId));
    }
    const benchmarks=Object.fromEntries(problems.map(problem=>[problem.id,{
      ...copy(problem.benchmark),
      id:`${contestId}-${problem.id}-${gpu.id}-${problem.benchmark.testVersion}`,
      profileId:gpu.profileId,
      gpu:gpu.label,
      referenceMs:Number((problem.benchmark.referenceMs/(gpuProfiles.find(profile=>profile.profileId===problem.benchmark.profileId)?.referenceScale||1)*gpu.referenceScale).toFixed(6)),
      demo:true
    }]));
    const problemSnapshots=Object.fromEntries(problems.map(problem=>[problem.id,{...copy(problem),benchmark:copy(benchmarks[problem.id])}]));
    const item=old||{id:contestId,ownerId:state.sessionId,createdAt:nowIso()};
    Object.assign(item,{title,authorIds,startAt:new Date(startAt).toISOString(),endAt:new Date(endAt).toISOString(),durationMinutes,gpuId,problemIds,registrationOpen:true,benchmarks,problemSnapshots,updatedAt:nowIso()});
    if(!old)state.contests.push(item);
    persist();
    return {ok:true,contest:copy(item)};
  }
  function filteredSubmissions(filters={}) {
    return state.submissions.filter(item=>findProblem(item.problemId,item.contestId||null)&&(filters.userId===undefined||item.userId===filters.userId)&&(filters.problemId===undefined||item.problemId===filters.problemId)&&(filters.contestId===undefined||item.contestId===filters.contestId)).sort((a,b)=>Date.parse(b.createdAt)-Date.parse(a.createdAt)||b.id-a.id);
  }
  function canCompare(item) {
    if (!item||item.status!=='accepted'||!Number.isFinite(item.timeMs)||item.timeMs<=0) return false;
    const problem=findProblem(item.problemId);
    const expected=item.contestId?findContest(item.contestId)?.benchmarks[item.problemId]:problem?.benchmark;
    return !!expected&&item.profileId===expected.profileId&&item.testVersion===expected.testVersion&&item.benchmarkId===expected.id;
  }
  function hasSolved(problemId,userId=state.sessionId,contestId=null) {
    if(!userId)return false;
    const expected=findProblem(problemId,contestId)?.benchmark;
    return !!expected&&state.submissions.some(item=>item.userId===userId&&item.problemId===problemId&&(!contestId||item.contestId===contestId)&&['accepted','measurement-error'].includes(item.status)&&item.totalTests>0&&item.passedTests===item.totalTests&&item.testVersion===expected.testVersion);
  }
  function dayStreak(userId=state.sessionId) {
    if (!userId) return 0;
    const days = new Set();
    state.submissions.forEach(item => {
      if (item.userId !== userId) return;
      if (item.status !== 'accepted' && !(item.status === 'measurement-error' && item.passedTests === item.totalTests)) return;
      days.add(new Date(item.createdAt).toISOString().slice(0, 10));
    });
    if (!days.size) return 0;
    let streak = 0;
    const cursor = new Date();
    cursor.setUTCHours(0, 0, 0, 0);
    while (days.has(cursor.toISOString().slice(0, 10))) {
      streak += 1;
      cursor.setUTCDate(cursor.getUTCDate() - 1);
    }
    return streak;
  }
  function best(problemId,options={}) {
    const userId=options.userId===undefined?state.sessionId:options.userId;
    const contestId=options.contestId===undefined?null:options.contestId;
    if (!userId) return null;
    return filteredSubmissions({userId,problemId,contestId}).filter(canCompare).sort((a,b)=>a.timeMs-b.timeMs||Date.parse(a.createdAt)-Date.parse(b.createdAt)||a.id-b.id)[0]||null;
  }
  function leaderboard(problemId,contestId=null) {
    const rows=state.users.map(user=>({user,submission:best(problemId,{userId:user.id,contestId})})).filter(item=>item.submission).sort((a,b)=>a.submission.timeMs-b.submission.timeMs||a.user.handle.localeCompare(b.user.handle));
    let previous;
    let rank=0;
    return rows.map((item,index)=>{if(item.submission.timeMs!==previous)rank=index+1;previous=item.submission.timeMs;return {...item,rank};});
  }
  function standings(contestId) {
    const item=findContest(contestId);
    if (!item) return [];
    const ids=Array.from(new Set([...state.registrations.filter(row=>row.contestId===contestId).map(row=>row.userId),...state.submissions.filter(row=>row.contestId===contestId).map(row=>row.userId)]));
    const start=Date.parse(item.startAt);
    const rows=ids.map(userId=>{
      const user=findPerson(userId);
      const times={};
      item.problemIds.forEach(problemId=>{
        const accepted=state.submissions.filter(row=>row.userId===userId&&row.problemId===problemId&&row.contestId===contestId&&row.status==='accepted');
        if(accepted.length)times[problemId]=Math.max(0,Math.floor((Math.min(...accepted.map(row=>Date.parse(row.createdAt)))-start)/1000));
      });
      return {rank:null,user,solved:Object.keys(times).length,totalSeconds:Object.values(times).reduce((sum,value)=>sum+value,0),times};
    }).filter(row=>row.user);
    rows.sort((a,b)=>b.solved-a.solved||a.totalSeconds-b.totalSeconds||a.user.handle.localeCompare(b.user.handle));
    let previous;
    let rank=0;
    return rows.map((row,index)=>{
      const key=`${row.solved}|${row.totalSeconds}`;
      if(key!==previous)rank=index+1;
      previous=key;
      return {...row,rank};
    });
  }
  function profile(id) {
    const user=findPerson(id);
    if (!user) return null;
    const own=filteredSubmissions({userId:id});
    const solved=allProblems().filter(problem=>hasSolved(problem.id,id)).length;
    const contestIds=Array.from(new Set(own.filter(item=>item.contestId).map(item=>item.contestId)));
    const contestHistory=contestIds.map(contestId=>{
      const event=state.ratingEvents.find(item=>item.userId===id&&item.contestId===contestId);
      const row=standings(contestId).find(item=>item.user.id===id);
      return {contestId,rank:row?.rank??null,phase:phase(contestId),ratingDelta:event?.ratingDelta??null,ratingAfter:event?.ratingAfter??null};
    }).sort((a,b)=>Date.parse(findContest(b.contestId).startAt)-Date.parse(findContest(a.contestId).startAt));
    return {...user,solved,contestCount:contestHistory.length,recentSubmissions:own.slice(0,10),contestHistory};
  }
  const draftKey = (problemId,context='practice',userId=state.sessionId) => `${userId||'guest'}|${context||'practice'}|${problemId}`;
  function draft(problemId,context='practice') {
    const saved=state.drafts[draftKey(problemId,context)];
    return copy(saved||{code:findProblem(problemId,context==='practice'?null:context)?.starter||'',updatedAt:null,revision:0});
  }
  function saveDraft(problemId,context,code) {
    const key=draftKey(problemId,context);
    const old=draft(problemId,context);
    if(old.code===String(code))return old;
    state.drafts[key]={code:String(code),updatedAt:nowIso(),revision:old.revision+1};
    persist();
    return copy(state.drafts[key]);
  }
  function setSession(id) {
    if(id!==null&&!findPerson(id))return {ok:false,error:'Участник не найден.'};
    if(id&&state.sessionId===null){
      const pending=(state.returnTo||'').replace(/^#/,'');
      Object.keys(state.drafts).filter(key=>key.startsWith('guest|')).forEach(key=>{
        const destination=`${id}|${key.slice(6)}`;
        const [,context,problemId]=key.split('|');
        const matches=pending.startsWith(`/problem/${problemId}`)&&(pending.includes(`contest=${context}`)||context==='practice'&&!pending.includes('contest='));
        if(!state.drafts[destination]||matches){state.drafts[destination]=copy(state.drafts[key]);delete state.drafts[key];}
      });
    }
    state.sessionId=id;
    persist();
    return {ok:true,user:copy(current())};
  }
  function findUser(identifier) {
    const value=String(identifier||'').trim().toLowerCase();
    return state.users.find(item=>item.id.toLowerCase()===value||item.handle.toLowerCase()===value||item.email.toLowerCase()===value)||null;
  }
  const api={
    storageKey,
    storageError:()=>storageError,
    problems:()=>copy(allProblems()),
    contestProblems:()=>copy(contestProblems()),
    problem:(id,contestId=null)=>copy(findProblem(id,contestId)),
    users:()=>copy(state.users),
    user:()=>copy(current()),
    profile:id=>copy(profile(id)),
    findUser:identifier=>copy(findUser(identifier)),
    setSession,
    login(identifier){const user=findUser(identifier);return user?setSession(user.id):{ok:false,error:'Участник не найден. Для демо используйте alex_cuda или new_cuda.'};},
    logout:()=>setSession(null),
    register({handle,email}={}){
      handle=String(handle||'').trim();email=String(email||'').trim().toLowerCase();
      if(!/^[A-Za-z0-9_]{3,24}$/.test(handle))return {ok:false,error:'Имя должно содержать 3-24 латинские буквы, цифры или знак _.'};
      if(!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email))return {ok:false,error:'Укажите корректную почту.'};
      if(state.users.some(item=>item.handle.toLowerCase()===handle.toLowerCase()))return {ok:false,error:'Это имя уже занято.'};
      if(state.users.some(item=>item.email.toLowerCase()===email))return {ok:false,error:'Эта почта уже используется.'};
      const user={id:handle,handle,email,role:'participant',rating:null,ratingDelta:null,ratingContestId:null};
      state.users.push(user);return setSession(user.id);
    },
    setReturnTo(hash){state.returnTo=typeof hash==='string'&&/^#?\//.test(hash)?hash:null;persist();},
    takeReturnTo(){const result=state.returnTo;state.returnTo=null;persist();return result;},
    contests:()=>copy(state.contests.map(item=>({...item,registrationOpen:item.registrationOpen&&phase(item.id)!=='finished'}))),
    contest:id=>{const item=findContest(id);return item?copy({...item,registrationOpen:item.registrationOpen&&phase(id)!=='finished'}):null;},
    gpuProfiles:()=>gpuProfiles.map(({id,label})=>({id,label})),
    saveContest,
    canEditContest,
    isContestAuthor,
    phase,
    setContestPhase(id,value){
      const item=findContest(id);
      if(!item||!['upcoming','live','finished'].includes(value))return {ok:false,error:'Соревнование не найдено.'};
      const clock=Date.now();
      const durationMinutes=Number.isInteger(item.durationMinutes)&&item.durationMinutes>0?item.durationMinutes:Math.max(1,Math.round((Date.parse(item.endAt)-Date.parse(item.startAt))/60000)||120);
      const duration=durationMinutes*60000;
      const startAt=value==='upcoming'?clock+3600000:value==='live'?clock-Math.min(3600000,duration/2):clock-3600000-duration;
      item.startAt=new Date(startAt).toISOString();
      item.endAt=new Date(startAt+duration).toISOString();
      item.durationMinutes=durationMinutes;
      item.registrationOpen=value!=='finished';
      persist();
      return {ok:true,contest:copy(item)};
    },
    joined:(contestId,userId=state.sessionId)=>!!userId&&state.registrations.some(item=>item.contestId===contestId&&item.userId===userId),
    joinContest(id){if(!current())return {ok:false,error:'Войдите, чтобы участвовать.'};const item=findContest(id);if(!item)return {ok:false,error:'Соревнование не найдено.'};if(phase(id)==='finished'||!item.registrationOpen)return {ok:false,error:'Регистрация закрыта.'};if(!api.joined(id))state.registrations.push({contestId:id,userId:state.sessionId});persist();return {ok:true};},
    submissions:filters=>copy(filteredSubmissions(filters)),
    submission:id=>{const item=state.submissions.find(row=>String(row.id)===String(id));return item&&findProblem(item.problemId,item.contestId||null)?copy(item):null;},
    best:(problemId,options)=>copy(best(problemId,options)),
    leaderboard:(problemId,contestId)=>copy(leaderboard(problemId,contestId)),
    standings:contestId=>copy(standings(contestId)),
    canCompare,
    solved:hasSolved,
    streak:(userId)=>dayStreak(userId),
    draft,
    saveDraft,
    resetDraft(problemId,context='practice'){const snapshot=draft(problemId,context);saveDraft(problemId,context,findProblem(problemId,context==='practice'?null:context)?.starter||'');return snapshot;},
    restoreDraft:(problemId,context,snapshot)=>saveDraft(problemId,context,snapshot?.code||''),
    submit({problemId,contestId=null,code}={}){
      if(!current())return {ok:false,error:'Войдите, чтобы отправить решение.'};
      const problem=findProblem(problemId,contestId);
      if(!problem)return {ok:false,error:'Задача не найдена.'};
      if(!String(code||'').trim())return {ok:false,error:'Добавьте код решения.'};
      let benchmark=problem.benchmark;
      if(contestId){const item=findContest(contestId);if(!item||!item.problemIds.includes(problemId))return {ok:false,error:'Задача не входит в это соревнование.'};if(phase(contestId)!=='live')return {ok:false,error:phase(contestId)==='upcoming'?'Соревнование еще не началось.':findProblem(problemId)?'Соревнование завершено. Решение можно отправить в практике.':'Соревнование завершено. Прием решений закрыт.'};if(!api.joined(contestId))return {ok:false,error:'Сначала зарегистрируйтесь на соревнование.'};benchmark=item.benchmarks[problemId];}
      const submission={id:state.nextSubmissionId++,userId:state.sessionId,problemId,contestId,status:'queued',timeMs:null,createdAt:nowIso(),code:String(code),format:problem.format,templateId:problem.format,profileId:benchmark.profileId,testVersion:benchmark.testVersion,benchmarkId:benchmark.id,benchmark:copy(benchmark),limits:copy(problem.limits),tolerance:problem.tolerance,benchmarkSnapshot:copy(benchmark),previousBestMs:best(problemId,{userId:state.sessionId,contestId})?.timeMs??null,demoOutcome:'accepted',passedTests:0,totalTests:24,diagnostic:null};
      state.submissions.push(submission);persist();return {ok:true,submission:copy(submission)};
    },
    updateSubmission(id,patch={}){
      const item=state.submissions.find(row=>String(row.id)===String(id));
      if(!item)return null;
      const allowed=['status','timeMs','diagnostic','passedTests','profileId','testVersion','benchmarkId','benchmark','benchmarkSnapshot','demoOutcome'];
      allowed.forEach(key=>{if(Object.prototype.hasOwnProperty.call(patch,key))item[key]=copy(patch[key]);});
      if(patch.status&&!Object.prototype.hasOwnProperty.call(patch,'passedTests'))item.passedTests=['accepted','measurement-error','measuring'].includes(patch.status)?item.totalTests:0;
      if(patch.status&&!Object.prototype.hasOwnProperty.call(patch,'diagnostic')&&['queued','compiling','checking','measuring','accepted'].includes(patch.status))item.diagnostic=null;
      item.benchmark=copy(patch.benchmarkSnapshot||patch.benchmark||item.benchmarkSnapshot||item.benchmark);
      if(patch.profileId)item.benchmark.profileId=patch.profileId;
      if(patch.testVersion)item.benchmark.testVersion=patch.testVersion;
      if(patch.benchmarkId)item.benchmark.id=patch.benchmarkId;
      item.benchmarkSnapshot=copy(item.benchmark);
      if(item.status==='accepted'){item.passedTests=item.totalTests;if(!Number.isFinite(item.timeMs)||item.timeMs<=0)item.timeMs=Number((item.benchmark.referenceMs*0.8).toFixed(4));item.diagnostic=null;}
      else if(item.status==='measurement-error'){item.passedTests=item.totalTests;item.timeMs=null;}
      else item.timeMs=null;
      item.updatedAt=nowIso();persist();return copy(item);
    },
    resetDemo(){state=makeSeed();persist();return {ok:true};}
  };
  persist();
  window.AppModel=api;
})();
