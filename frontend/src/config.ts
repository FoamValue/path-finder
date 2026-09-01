/**
 * 全局配置：通过 Vite 环境变量覆盖，参见 .env.example
 */
export const APP_COPYRIGHT: string = (import.meta.env.VITE_COPYRIGHT as string | undefined) || 'chenxinjie';

export const APP_NAME: string = 'PathFinder';
