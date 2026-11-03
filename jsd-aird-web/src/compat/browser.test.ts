export {};

const names = ['toSorted', 'toReversed', 'toSpliced', 'with', 'findLast', 'findLastIndex'] as const;
const originals = names.map(name => Object.getOwnPropertyDescriptor(Array.prototype, name));
type ModernArray = number[] & {
  toSorted: (compare?: (a: number, b: number) => number) => number[];
  toReversed: () => number[];
  toSpliced: (start: number, count: number, ...items: number[]) => number[];
  with: (index: number, value: number) => number[];
  findLast: (predicate: (value: number) => boolean) => number | undefined;
  findLastIndex: (predicate: (value: number) => boolean) => number;
};

beforeAll(async () => {
  names.forEach(name => Reflect.deleteProperty(Array.prototype, name));
  await import('./browser');
});

afterAll(() => {
  names.forEach((name, index) => {
    const descriptor = originals[index];
    if (descriptor) Object.defineProperty(Array.prototype, name, descriptor);
    else Reflect.deleteProperty(Array.prototype, name);
  });
});

it('restores missing sorting without changing the source or making methods enumerable', () => {
  const source = [3, 1, 2] as ModernArray;
  expect(source.toSorted((a, b) => a - b)).toEqual([1, 2, 3]);
  expect(source).toEqual([3, 1, 2]);
  expect(Object.keys(source)).toEqual(['0', '1', '2']);
  expect(Object.getOwnPropertyDescriptor(Array.prototype, 'toSorted')?.enumerable).toBe(false);
});

it('provides the related copy and last-match methods', () => {
  const source = [3, 1, 2] as ModernArray;
  expect(source.toReversed()).toEqual([2, 1, 3]);
  expect(source.toSpliced(1, 1, 9)).toEqual([3, 9, 2]);
  expect(source.with(-1, 9)).toEqual([3, 1, 9]);
  expect(source.findLast(value => value > 1)).toBe(2);
  expect(source.findLastIndex(value => value > 1)).toBe(2);
  expect(source).toEqual([3, 1, 2]);
});

it('keeps method identity when the compatibility entry loads again', async () => {
  const sort = Object.getOwnPropertyDescriptor(Array.prototype, 'toSorted')?.value as unknown;
  await import('./browser');
  expect(Object.getOwnPropertyDescriptor(Array.prototype, 'toSorted')?.value).toBe(sort);
});
