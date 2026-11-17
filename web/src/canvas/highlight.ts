/**
 * 轻量语法着色。
 *
 * 关键约束：token 拼起来的文本必须与原文**逐字符相等**。
 * 因为选区锚点存的是「相对代码块纯文本的字符偏移」，
 * 着色只能包裹 span，不能插入、删除、替换任何字符（换行也必须原地保留）。
 */

export type TokClass = 'kw' | 'type' | 'fn' | 'str' | 'num' | 'com' | 'anno';

export interface Tok {
  text: string;
  cls?: TokClass;
}

const KEYWORDS: Record<string, Set<string>> = {
  java: new Set(
    `abstract assert break case catch class continue default do else enum extends final finally
     for if implements import instanceof interface native new package private protected public
     return static super switch synchronized this throw throws transient try var void volatile while
     record sealed yield true false null`.split(/\s+/),
  ),
  cpp: new Set(
    `alignas alignof auto bool break case catch char class const constexpr continue default delete do
     double else enum explicit extern false float for friend goto if inline int long mutable
     namespace new noexcept nullptr operator private protected public return short signed sizeof
     static struct switch template this throw true try typedef typename union unsigned using virtual
     void volatile while`.split(/\s+/),
  ),
  python: new Set(
    `and as assert async await break class continue def del elif else except finally for from global
     if import in is lambda nonlocal not or pass raise return True False None try while with yield
     match case print`.split(/\s+/),
  ),
  javascript: new Set(
    `async await break case catch class const continue debugger default delete do else export extends
     false finally for from function if import in instanceof let new null of return static super
     switch this throw true try typeof var void while yield undefined`.split(/\s+/),
  ),
  go: new Set(
    `break case chan const continue default defer else fallthrough for func go goto if import
     interface map package range return select struct switch type var nil true false`.split(/\s+/),
  ),
};

const TYPES: Record<string, Set<string>> = {
  java: new Set(
    `String Integer Long Double Float Boolean Character Byte Short Object List Map Set ArrayList
     LinkedList HashMap HashSet StringBuilder StringBuffer System Math Arrays Collections Optional
     Stream Queue Deque Stack Iterator Comparator Runnable Exception`.split(/\s+/),
  ),
  cpp: new Set(
    `std string vector map unordered_map set unordered_set pair queue stack deque size_t int8_t
     int16_t int32_t int64_t uint32_t uint64_t nullptr_t`.split(/\s+/),
  ),
  python: new Set(`int float str bool list dict set tuple range len self object`.split(/\s+/)),
  javascript: new Set(
    `Array Object String Number Boolean Promise Map Set WeakMap JSON Math Date RegExp Error console
     window document`.split(/\s+/),
  ),
  go: new Set(
    `int int8 int16 int32 int64 uint uint8 uint16 uint32 uint64 float32 float64 string bool byte rune
     error any`.split(/\s+/),
  ),
};

const ALIAS: Record<string, string> = {
  js: 'javascript',
  jsx: 'javascript',
  ts: 'javascript',
  typescript: 'javascript',
  tsx: 'javascript',
  py: 'python',
  'c++': 'cpp',
  cc: 'cpp',
  cxx: 'cpp',
  golang: 'go',
};

const IDENT_START = /[A-Za-z_$]/;
const IDENT_PART = /[A-Za-z0-9_$]/;

export function isHighlightedLanguage(language: string) {
  const lang = normalizeLang(language);
  return lang !== 'text';
}

function normalizeLang(language: string) {
  const raw = (language || 'text').toLowerCase().trim();
  const lang = ALIAS[raw] ?? raw;
  return KEYWORDS[lang] ? lang : 'text';
}

export function tokenize(content: string, language: string): Tok[] {
  const lang = normalizeLang(language);
  if (lang === 'text' || !content) return [{ text: content }];

  const kw = KEYWORDS[lang];
  const types = TYPES[lang];
  const isPython = lang === 'python';

  const out: Tok[] = [];
  let plain = '';
  const push = (text: string, cls?: TokClass) => {
    if (!text) return;
    if (!cls) {
      plain += text;
      return;
    }
    if (plain) {
      out.push({ text: plain });
      plain = '';
    }
    out.push({ text, cls });
  };

  const n = content.length;
  let i = 0;

  while (i < n) {
    const c = content[i];
    const pair = content.slice(i, i + 2);

    // 行注释
    if ((isPython && c === '#') || (!isPython && pair === '//')) {
      let end = content.indexOf('\n', i);
      if (end === -1) end = n;
      push(content.slice(i, end), 'com');
      i = end;
      continue;
    }

    // 块注释
    if (!isPython && pair === '/*') {
      const close = content.indexOf('*/', i + 2);
      const end = close === -1 ? n : close + 2;
      push(content.slice(i, end), 'com');
      i = end;
      continue;
    }

    // Python 三引号
    if (isPython && (content.startsWith('"""', i) || content.startsWith("'''", i))) {
      const q = content.slice(i, i + 3);
      const close = content.indexOf(q, i + 3);
      const end = close === -1 ? n : close + 3;
      push(content.slice(i, end), 'str');
      i = end;
      continue;
    }

    // 字符串（含转义；单行未闭合时到行尾为止，避免整篇被染成一个字符串）
    if (c === '"' || c === "'" || c === '`') {
      let j = i + 1;
      while (j < n) {
        if (content[j] === '\\') {
          j += 2;
          continue;
        }
        if (content[j] === c) {
          j += 1;
          break;
        }
        if (content[j] === '\n' && c !== '`') break;
        j += 1;
      }
      push(content.slice(i, j), 'str');
      i = j;
      continue;
    }

    // 数字
    if (c >= '0' && c <= '9') {
      const m =
        /^(?:0[xX][0-9a-fA-F_]+|0[bB][01_]+|0[oO][0-7_]+|\d[\d_]*(?:\.\d+)?(?:[eE][+-]?\d+)?)(?:[a-zA-Z]{1,2})?/.exec(
          content.slice(i),
        );
      const text = m ? m[0] : c;
      push(text, 'num');
      i += text.length;
      continue;
    }

    // Java 注解
    if (c === '@' && lang === 'java' && IDENT_START.test(content[i + 1] ?? '')) {
      let j = i + 1;
      while (j < n && IDENT_PART.test(content[j])) j += 1;
      push(content.slice(i, j), 'anno');
      i = j;
      continue;
    }

    // 标识符：关键字 / 类型 / 函数调用
    if (IDENT_START.test(c)) {
      let j = i + 1;
      while (j < n && IDENT_PART.test(content[j])) j += 1;
      const word = content.slice(i, j);

      let cls: TokClass | undefined;
      if (kw.has(word)) {
        cls = 'kw';
      } else if (types.has(word) || /^[A-Z][A-Za-z0-9_]*$/.test(word)) {
        cls = 'type';
      } else {
        // 往后看第一个非空白字符，是 ( 就当作函数名
        let k = j;
        while (k < n && (content[k] === ' ' || content[k] === '\t')) k += 1;
        if (content[k] === '(') cls = 'fn';
      }

      push(word, cls);
      i = j;
      continue;
    }

    push(c);
    i += 1;
  }

  if (plain) out.push({ text: plain });
  return out;
}
