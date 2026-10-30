// Load before React and lazy Office runtimes. Vite transpiles syntax, but
// dependencies such as Univer also need these APIs on Chromium 106.
// core-js preserves working native implementations and never sorts in place.
import 'core-js/actual/array/to-sorted';
import 'core-js/actual/array/to-reversed';
import 'core-js/actual/array/to-spliced';
import 'core-js/actual/array/with';
import 'core-js/actual/array/find-last';
import 'core-js/actual/array/find-last-index';
