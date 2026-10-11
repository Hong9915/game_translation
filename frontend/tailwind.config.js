/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        ink: '#1a1f26',
        body: '#4b5563',
        muted: '#8a94a3',
        line: '#d8dee6',
        lineSoft: '#eaeef3',
        canvas: '#f5f7fa',
        tableHead: '#f3f5f8',
        primary: '#1f4b99',
        primarySoft: '#e4ecfa',
        green: '#1e7a5a',
        greenSoft: '#ddf3e6',
        purple: '#6b3fa0',
        purpleSoft: '#eee6f8',
        orange: '#c4571a',
        orangeSoft: '#fdebd9',
      },
      fontFamily: {
        sans: ['"Pretendard Variable"', 'Pretendard', '-apple-system', 'BlinkMacSystemFont', 'system-ui', 'sans-serif'],
      },
    },
  },
  plugins: [],
}
