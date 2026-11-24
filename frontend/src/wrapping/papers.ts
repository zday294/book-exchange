/**
 * The wrapping papers. The front end owns how they look; the backend only validates the key,
 * so these keys MUST match backend/src/main/java/com/bookexchange/book/WrappingPapers.java
 * (a test compares the two lists).
 */
export type PatternType = 'stripes' | 'snowflakes' | 'holly' | 'stars' | 'plaid' | 'dots' | 'cookies' | 'trees' | 'fireplace' | 'sleighs'

export interface WrappingPaper {
  key: string
  label: string
  pattern: PatternType
  /** Background color of the paper. */
  base: string
  /** Color of the pattern's main motif. */
  accent: string
  /** Secondary motif color (e.g. berries, plaid lines). */
  accent2: string
  ribbon: string
  ribbonShade: string
}

export const WRAPPING_PAPERS: readonly WrappingPaper[] = [
  {
    key: 'candy-cane', label: 'Candy cane', pattern: 'stripes',
    base: '#fbf3ea', accent: '#c8202f', accent2: '#c8202f', ribbon: '#1f6b45', ribbonShade: '#154c31',
  },
  {
    key: 'snowflake', label: 'Snowflake', pattern: 'snowflakes',
    base: '#2b5f8e', accent: '#ffffff', accent2: '#bfdcf2', ribbon: '#c8202f', ribbonShade: '#8f1520',
  },
  {
    key: 'holly', label: 'Holly', pattern: 'holly',
    base: '#1f5b3a', accent: '#3f9a63', accent2: '#d8283a', ribbon: '#c8962e', ribbonShade: '#946b17',
  },
  {
    key: 'stars', label: 'Stars', pattern: 'stars',
    base: '#1d2b5a', accent: '#f4c542', accent2: '#ffe9a0', ribbon: '#c8202f', ribbonShade: '#8f1520',
  },
  {
    key: 'plaid', label: 'Plaid', pattern: 'plaid',
    base: '#b3202a', accent: '#1b3d2a', accent2: '#f3e6c8', ribbon: '#f3e6c8', ribbonShade: '#cdbb94',
  },
  {
    key: 'polka-dot', label: 'Polka dot', pattern: 'dots',
    base: '#1f7a7a', accent: '#ffffff', accent2: '#f4c542', ribbon: '#c8202f', ribbonShade: '#8f1520',
  },
  {
    key: 'sugar-cookie', label: 'Cookie', pattern: 'cookies',
    base: '#f3dfb6', accent: '#e2b878', accent2: '#c8202f', ribbon: '#c8202f', ribbonShade: '#8f1520',
  },
  {
    key: 'christmas-tree', label: 'Tree', pattern: 'trees',
    base: '#dcebf2', accent: '#1f6b45', accent2: '#c8202f', ribbon: '#c8202f', ribbonShade: '#8f1520',
  },
  {
    key: 'fireplace', label: 'Fireplace', pattern: 'fireplace',
    base: '#8a3b2a', accent: '#f08a24', accent2: '#ffd23f', ribbon: '#c8962e', ribbonShade: '#946b17',
  },
  {
    key: 'sleigh', label: 'Sleigh', pattern: 'sleighs',
    base: '#43286b', accent: '#c8202f', accent2: '#f4c542', ribbon: '#f4c542', ribbonShade: '#b8901a',
  },
]

export const DEFAULT_PAPER: WrappingPaper = WRAPPING_PAPERS[0]

export const PAPER_KEYS: readonly string[] = WRAPPING_PAPERS.map((p) => p.key)

/** Finds a paper by key; an unknown or missing key falls back to the default instead of breaking the page. */
export function resolvePaper(key: string | null | undefined): WrappingPaper {
  return WRAPPING_PAPERS.find((p) => p.key === key) ?? DEFAULT_PAPER
}
