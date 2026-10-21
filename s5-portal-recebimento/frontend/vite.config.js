import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// O build vai direto para os recursos estáticos do Quarkus: um único executável serve API e tela.
export default defineConfig({
  plugins: [react()],
  build: { outDir: '../src/main/resources/META-INF/resources', emptyOutDir: true },
  server: { port: 5173, proxy: { '/api': 'http://localhost:8090' } },
})
