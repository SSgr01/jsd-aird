import katex from 'katex';
import 'katex/dist/katex.min.css';

export function normalizeMineruLatex(value: string) {
  return value
    .trim()
    .replace(/\\_\s*(?=\{)/g, '_')
    .replace(/\\mathrm\s*\{([^{}]*)\}/g, (_, body: string) => `\\mathrm{${body.replace(/\s+/g, '')}}`)
    .replace(/\\(mathbf|mathit|mathsf|mathtt)\s*\{([^{}]*)\}/g, (_, command: string, body: string) => {
      const tokens = body.trim().split(/\s+/);
      const normalizedBody = tokens.length > 1 && tokens.every((token) => /^[A-Za-z0-9]$/.test(token))
        ? tokens.join('')
        : body.trim();
      return `\\${command}{${normalizedBody}}`;
    })
    .replace(/(?<![\p{L}\p{N}])(?:\d\s+)+\d(?![\p{L}\p{N}])/gu, (digits) => digits.replace(/\s+/g, ''))
    .replace(/\s*([_^])\s*(?=\{)/g, '$1');
}

export function renderLatexInto(element: HTMLElement, latexRaw: string, displayMode = false) {
  const renderLatex = normalizeMineruLatex(latexRaw);
  element.replaceChildren();
  element.dataset.latexRaw = latexRaw;
  element.dataset.renderLatex = renderLatex;
  try {
    katex.render(renderLatex, element, {
      displayMode,
      output: 'htmlAndMathml',
      strict: 'ignore',
      throwOnError: true,
      trust: false,
    });
    element.removeAttribute('data-math-error');
  } catch {
    element.textContent = latexRaw;
    element.dataset.mathError = 'true';
  }
}

export function latexHtml(latexRaw: string, displayMode = false) {
  return katex.renderToString(normalizeMineruLatex(latexRaw), {
    displayMode,
    output: 'htmlAndMathml',
    strict: 'ignore',
    throwOnError: false,
    trust: false,
  });
}
