import type { Config } from 'tailwindcss';

// 设计系统主题扩展（严格依据 docs §3.5，色值不得改动）
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        paper: '#FAF9F7',
        surface: '#FFFFFF',
        'surface-2': '#F3F1ED',
        ink: { DEFAULT: '#1C1B1A', soft: '#57534E', faint: '#8A857E' },
        line: { DEFAULT: '#E8E5DF', strong: '#D8D4CC' },
        brand: { DEFAULT: '#1C1B1A', hover: '#38332E', strong: '#000000', soft: '#ECEAE6' },
        accent: { DEFAULT: '#2563EB', hover: '#1D4ED8', strong: '#1E40AF', soft: '#EAF1FB' },
        danger: { DEFAULT: '#DC2626', soft: '#FDECEC' },
        warn: { DEFAULT: '#C2780A', soft: '#FBF1DD' },
        ok: { DEFAULT: '#2F855A', soft: '#E6F2EB' },
        info: { DEFAULT: '#2563EB', soft: '#EAF1FB' },
        amber: { DEFAULT: '#B7791F' },
      },
      fontFamily: {
        sans: ['"IBM Plex Sans"', '"PingFang SC"', '"Microsoft YaHei"', '"Source Han Sans SC"', '"Noto Sans SC"', 'system-ui', 'sans-serif'],
        mono: ['"JetBrains Mono"', '"IBM Plex Mono"', 'ui-monospace', 'SFMono-Regular', 'Consolas', 'monospace'],
      },
      fontSize: {
        '2xs': ['11px', { lineHeight: '16px' }],
        'xs': ['12px', { lineHeight: '18px' }],
        'sm': ['13px', { lineHeight: '20px' }],
        'base': ['14px', { lineHeight: '22px' }],
        'md': ['15px', { lineHeight: '24px' }],
        'lg': ['18px', { lineHeight: '28px' }],
        'xl': ['22px', { lineHeight: '32px' }],
        '2xl': ['28px', { lineHeight: '38px' }],
        '3xl': ['36px', { lineHeight: '46px' }],
      },
      spacing: {
        '0.5': '2px', '1': '4px', '2': '8px', '3': '12px', '4': '16px', '5': '20px',
        '6': '24px', '8': '32px', '10': '40px', '12': '48px', '16': '64px', '20': '80px',
      },
      borderRadius: {
        sm: '4px', DEFAULT: '8px', md: '8px', lg: '10px', xl: '12px', '2xl': '16px',
      },
      boxShadow: {
        card: '0 1px 2px rgba(28,27,26,0.04), 0 1px 3px rgba(28,27,26,0.06)',
        pop: '0 6px 16px rgba(28,27,26,0.10)',
        focus: '0 0 0 3px rgba(37,99,235,0.20)',
      },
      transitionDuration: { fast: '120ms', base: '180ms', slow: '260ms' },
      transitionTimingFunction: { smooth: 'cubic-bezier(0.4, 0, 0.2, 1)' },
    },
  },
  plugins: [],
} satisfies Config;
