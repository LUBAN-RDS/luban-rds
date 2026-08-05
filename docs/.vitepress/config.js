import { defineConfig } from 'vitepress'

export default defineConfig({
  title: 'Luban-RDS 文档',
  description: '轻量级、高性能的内存键值存储，兼容 Redis 协议',
  base: '/luban-rds/',
  vite: {
    optimizeDeps: {
      include: ['@vueuse/core']
    },
    build: {
      minify: 'esbuild',
      chunkSizeWarningLimit: 1000,
      cssCodeSplit: true,
      sourcemap: false
    }
  },
  themeConfig: {
    nav: [
      {
        text: '首页',
        link: '/'
      },
      {
        text: '使用指南',
        items: [
          { text: '快速开始', link: '/guide/quickstart' },
          { text: '基础操作', link: '/guide/basic-usage' },
          { text: '高级功能', link: '/guide/advanced' },
          { text: '使用示例', link: '/guide/examples' },
          { text: '性能基准测试', link: '/guide/benchmarking' }
        ]
      },
      {
        text: '集群方案',
        items: [
          { text: 'Redis Cluster 集群部署', link: '/deployment/cluster-setup' },
          { text: 'Mesh 集群（3 节点 Raft）', link: '/mesh/index' }
        ]
      },
      {
        text: '技术文档',
        items: [
          { text: 'API 参考', link: '/api/' },
          { text: 'Lua 脚本', link: '/lua/' },
          { text: '架构设计', link: '/architecture/' }
        ]
      },
      {
        text: '部署运维',
        link: '/deployment/'
      },
      {
        text: '开发指南',
        link: '/development/'
      },
      {
        text: '更多',
        items: [
          { text: '资源中心', link: '/resources/' },
          { text: '法律信息', link: '/legal/' },
          { text: '更新日志', link: '/changelog' }
        ]
      }
    ],
    sidebar: {
      '/guide/': [
        {
          text: '使用指南',
          collapsible: true,
          items: [
            { text: '快速开始', link: '/guide/quickstart' },
            { text: '基础操作', link: '/guide/basic-usage' },
            { text: '高级功能', link: '/guide/advanced' },
            { text: '使用示例', link: '/guide/examples' },
            { text: '性能基准测试', link: '/guide/benchmarking' }
          ]
        }
      ],
      '/api/': [
        {
          text: 'API 参考',
          collapsible: true,
          items: [
            { text: '核心接口', link: '/api/core' },
            { text: '命令列表', link: '/api/commands' },
            { text: '协议说明', link: '/api/protocol' }
          ]
        }
      ],
      '/lua/': [
        {
          text: 'Lua 脚本',
          collapsible: true,
          items: [
            { text: '使用指南', link: '/lua/usage' },
            { text: 'API 参考', link: '/lua/api' }
          ]
        }
      ],
      '/architecture/': [
        {
          text: '架构设计',
          collapsible: true,
          items: [
            { text: '系统架构', link: '/architecture/system' },
            { text: '功能架构', link: '/architecture/features' },
            { text: '设计决策', link: '/architecture/design' }
          ]
        }
      ],
      '/mesh/': [
        {
          text: 'Mesh 集群（3 节点 Raft）',
          collapsible: true,
          items: [
            { text: '模块概览', link: '/mesh/index' },
            { text: '快速上手', link: '/mesh/setup' },
            { text: '协议设计要点', link: '/mesh/design' }
          ]
        }
      ],
      '/deployment/': [
        {
          text: '部署运维',
          collapsible: true,
          items: [
            { text: '安装部署', link: '/deployment/installation' },
            { text: '配置指南', link: '/deployment/configuration' },
            { text: '集群部署（Redis Cluster）', link: '/deployment/cluster-setup' },
            { text: '监控维护', link: '/deployment/monitoring' },
            { text: '故障排查', link: '/deployment/troubleshooting' }
          ]
        }
      ],
      '/development/': [
        {
          text: '开发指南',
          collapsible: true,
          items: [
            { text: '环境搭建', link: '/development/setup' },
            { text: '开发流程', link: '/development/process' },
            { text: '代码规范', link: '/development/standards' },
            { text: '测试指南', link: '/development/testing' },
            { text: '贡献指南', link: '/development/contributing' },
            { text: '开发路线图', link: '/development/roadmap' }
          ]
        }
      ],
      '/resources/': [
        {
          text: '资源中心',
          collapsible: true,
          items: [
            { text: '常见问题', link: '/resources/faq' },
            { text: '相关资源', link: '/resources/related' }
          ]
        }
      ],
      '/legal/': [
        {
          text: '法律信息',
          collapsible: true,
          items: [
            { text: '许可证', link: '/legal/license' },
            { text: '隐私政策', link: '/legal/privacy' },
            { text: '服务条款', link: '/legal/terms' }
          ]
        }
      ],
    },
    socialLinks: [
      {
        icon: 'github',
        link: 'https://github.com/LUBAN-RDS/luban-rds'
      }
    ],
    footer: {
      message: '基于 VitePress 构建',
      copyright: '© 2026 Luban-RDS 团队'
    }
  }
})
