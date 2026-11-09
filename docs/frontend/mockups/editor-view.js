(function () {
  const keywords = new Set('alignas alignof and and_eq asm auto bitand bitor break case catch class co_await co_return co_yield compl concept const consteval constexpr constinit const_cast continue decltype default delete do dynamic_cast else enum explicit export extern false for friend goto if inline mutable namespace new noexcept not not_eq nullptr operator or or_eq private protected public register reinterpret_cast requires return sizeof static static_assert static_cast struct switch template this thread_local throw true try typedef typeid typename union using virtual volatile while xor xor_eq __global__ __device__ __host__ __shared__ __constant__ __managed__ __restrict__ __forceinline__ __noinline__'.split(' '));
  const types = new Set('bool char char8_t char16_t char32_t double float int long short signed unsigned void wchar_t size_t ptrdiff_t int8_t int16_t int32_t int64_t uint8_t uint16_t uint32_t uint64_t dim3 uint3 float2 float3 float4 double2 double3 double4 int2 int3 int4 uint2 uint4 __half __half2 cudaError_t cudaStream_t cudaEvent_t'.split(' '));
  const builtins = new Set('blockIdx blockDim threadIdx gridDim warpSize'.split(' '));
  const whitespace = /\s+/y;
  const identifier = /[A-Za-z_][A-Za-z_0-9]*/y;
  const number = /(?:0[xX][0-9a-fA-F']+(?:\.[0-9a-fA-F']*)?(?:[pP][+-]?[0-9']+)?|0[bB][01']+|(?:[0-9][0-9']*(?:\.[0-9']*)?|\.[0-9][0-9']*)(?:[eE][+-]?[0-9']+)?)(?:[uUlLfFzZ]+|_[A-Za-z_][A-Za-z_0-9]*)?/y;
  const rawString = /(?:u8|u|U|L)?R"([^\s()\\]{0,16})\(/y;
  const stringStart = /(?:u8|u|U|L)?["']/y;
  const operators = /<<<|>>>|<=>|->\*|<<=|>>=|::|->|\+\+|--|&&|\|\||==|!=|<=|>=|\+=|-=|\*=|\/=|%=|&=|\|=|\^=|<<|>>|[+\-*\/%=<>!&|^~?:.]/y;
  let current = null;

  function escape(value) {
    return value.replace(/[&<>"']/g, char => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[char]));
  }

  function matchAt(pattern, source, index) {
    pattern.lastIndex = index;
    return pattern.exec(source);
  }

  function tokens(source) {
    const result = [];
    let index = 0;
    while (index < source.length) {
      const start = index;
      let kind = '';
      let match = matchAt(whitespace, source, index);
      if (match) index += match[0].length;
      else if (source.startsWith('//', index)) {
        const end = source.indexOf('\n', index);
        index = end < 0 ? source.length : end;
        kind = 'comment';
      } else if (source.startsWith('/*', index)) {
        const end = source.indexOf('*/', index + 2);
        index = end < 0 ? source.length : end + 2;
        kind = 'comment';
      } else if (source[index] === '#' && /^[\t ]*$/.test(source.slice(source.lastIndexOf('\n', index - 1) + 1, index))) {
        let end = source.indexOf('\n', index);
        while (end >= 0 && /\\\r?$/.test(source.slice(index, end))) end = source.indexOf('\n', end + 1);
        index = end < 0 ? source.length : end;
        kind = 'directive';
      } else if ((match = matchAt(rawString, source, index))) {
        const closing = ')' + match[1] + '"';
        const end = source.indexOf(closing, index + match[0].length);
        index = end < 0 ? source.length : end + closing.length;
        kind = 'string';
      } else if ((match = matchAt(stringStart, source, index))) {
        const quote = match[0].slice(-1);
        index += match[0].length;
        while (index < source.length) {
          if (source[index] === '\\') index = Math.min(source.length, index + 2);
          else if (source[index++] === quote) break;
          else if (source[index - 1] === '\n') break;
        }
        kind = 'string';
      } else if ((match = matchAt(number, source, index))) {
        index += match[0].length;
        kind = 'number';
      } else if ((match = matchAt(identifier, source, index))) {
        index += match[0].length;
        const word = match[0];
        kind = keywords.has(word) ? 'keyword' : types.has(word) ? 'type' : builtins.has(word) ? 'builtin' : /^\s*(?:\(|<<<)/.test(source.slice(index)) ? 'function' : '';
      } else if ((match = matchAt(operators, source, index))) {
        index += match[0].length;
        kind = 'operator';
      } else index++;
      result.push({text: source.slice(start, index), kind});
    }
    return result;
  }

  function highlight(source) {
    return tokens(String(source ?? '')).map(token => token.kind ? `<span class="tok-${token.kind}">${escape(token.text)}</span>` : escape(token.text)).join('');
  }

  function position(source, offset, tabSize) {
    const prefix = source.slice(0, offset);
    const line = prefix.split('\n').length;
    const tail = prefix.slice(prefix.lastIndexOf('\n') + 1);
    let column = 1;
    for (const char of tail) column += char === '\t' ? tabSize - (column - 1) % tabSize : char === '\r' ? 0 : 1;
    return {line, column};
  }

  function mount(textarea) {
    if (current?.textarea === textarea && textarea?.isConnected) {
      current.refresh();
      return current.destroy;
    }
    current?.destroy();
    if (!textarea) return null;
    const surface = textarea.closest('.code-surface');
    const area = textarea.closest('.editor-code-area');
    const gutter = surface?.querySelector('.editor-gutter');
    const gutterContent = surface?.querySelector('.gutter-content');
    const pre = area?.querySelector('.code-highlight');
    const code = pre?.querySelector('code');
    const activeLine = area?.querySelector('.code-active-line');
    const cursor = textarea.closest('.editor-section')?.querySelector('.cursor-position');
    if (!surface || !area || !gutter || !gutterContent || !pre || !code || !activeLine) return null;
    let lineHeight = 23;
    let paddingTop = 12;
    let tabSize = 4;
    let count = 0;
    let active = 0;
    let cachedSource = null;
    let destroyed = false;
    let resizeObserver;
    const listeners = [];
    const instance = {textarea, refresh, destroy};
    current = instance;

    function measure() {
      const style = getComputedStyle(textarea);
      lineHeight = Number.parseFloat(style.lineHeight) || 23;
      paddingTop = Number.parseFloat(style.paddingTop) || 0;
      tabSize = Number.parseInt(style.tabSize, 10) || 4;
      gutterContent.style.paddingTop = paddingTop + 'px';
      gutterContent.style.paddingBottom = style.paddingBottom;
      gutterContent.querySelectorAll('.gutter-line').forEach(line => {
        line.style.height = lineHeight + 'px';
        line.style.lineHeight = lineHeight + 'px';
      });
      pre.style.width = textarea.clientWidth + 'px';
      pre.style.height = textarea.clientHeight + 'px';
    }

    function drawSource() {
      const value = textarea.value;
      if (value !== cachedSource) {
        code.innerHTML = highlight(value);
        cachedSource = value;
      }
      const nextCount = value.split('\n').length;
      if (nextCount !== count) {
        count = nextCount;
        gutterContent.innerHTML = Array.from({length: count}, (_, index) => `<div class="gutter-line" data-line="${index + 1}" style="height:${lineHeight}px;line-height:${lineHeight}px">${index + 1}</div>`).join('');
        active = 0;
        const width = Math.max(48, String(count).length * 8 + 16);
        surface.style.gridTemplateColumns = width + 'px minmax(0, 1fr)';
        gutter.style.width = width + 'px';
      }
    }

    function sync() {
      if (destroyed) return;
      if (!textarea.isConnected) {destroy(); return;}
      const offset = textarea.selectionDirection === 'backward' ? textarea.selectionStart : textarea.selectionEnd;
      const caret = position(textarea.value, offset ?? 0, tabSize);
      if (caret.line !== active) {
        gutterContent.querySelector('.current')?.classList.remove('current');
        gutterContent.querySelector(`[data-line="${caret.line}"]`)?.classList.add('current');
        active = caret.line;
      }
      code.style.transform = `translate(${-textarea.scrollLeft}px, ${-textarea.scrollTop}px)`;
      gutterContent.style.transform = `translateY(${-textarea.scrollTop}px)`;
      const top = paddingTop + (caret.line - 1) * lineHeight - textarea.scrollTop;
      const visibleTop = Math.max(0, top);
      const height = Math.min(lineHeight + Math.min(0, top), textarea.clientHeight - visibleTop);
      activeLine.hidden = height <= 0 || visibleTop >= textarea.clientHeight;
      if (!activeLine.hidden) {
        activeLine.style.top = visibleTop + 'px';
        activeLine.style.height = height + 'px';
        activeLine.style.width = textarea.clientWidth + 'px';
      }
      if (cursor) {
        cursor.textContent = caret.line + ':' + caret.column;
        cursor.title = 'Строка ' + caret.line + ', столбец ' + caret.column;
      }
    }

    function refresh() {
      if (destroyed) return;
      if (!textarea.isConnected) {destroy(); return;}
      drawSource();
      measure();
      sync();
    }

    function listen(name, callback) {
      textarea.addEventListener(name, callback);
      listeners.push([name, callback]);
    }

    function destroy() {
      if (destroyed) return;
      destroyed = true;
      resizeObserver?.disconnect();
      listeners.forEach(([name, callback]) => textarea.removeEventListener(name, callback));
      area.classList.remove('enhanced');
      pre.hidden = true;
      activeLine.hidden = true;
      if (current === instance) current = null;
    }

    pre.hidden = false;
    area.classList.add('enhanced');
    listen('input', refresh);
    listen('scroll', sync);
    ['click', 'keyup', 'select', 'focus'].forEach(name => listen(name, sync));
    if (typeof ResizeObserver !== 'undefined') {
      resizeObserver = new ResizeObserver(() => {
        if (!textarea.isConnected) {destroy(); return;}
        measure();
        sync();
      });
      resizeObserver.observe(textarea);
    }
    document.fonts?.ready.then(() => {
      if (!destroyed && textarea.isConnected) {measure(); sync();}
    });
    refresh();
    return destroy;
  }

  window.EditorView = {mount, highlight};
})();
