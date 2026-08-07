import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';

// Ant Design 默认样式已由组件自动引入
import 'antd/dist/reset.css';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);