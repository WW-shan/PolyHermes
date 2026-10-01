import { create } from 'zustand'
import { apiService } from '../services/api'

interface AuthStore {
  /** 当前用户是否为管理员：null 表示尚未确认 */
  isAdmin: boolean | null
  /** 查询当前用户的管理员权限 */
  fetchAdminStatus: () => Promise<void>
  /** 登出时重置 */
  resetAdminStatus: () => void
}

export const useAuthStore = create<AuthStore>((set) => ({
  isAdmin: null,

  fetchAdminStatus: async () => {
    try {
      const response = await apiService.auth.verifyAdmin()
      set({ isAdmin: response.status === 200 && response.data?.code === 0 })
    } catch (error) {
      // 网络异常时无法确认权限，按非管理员处理（后端仍会拦截），下次进入页面重试
      console.error('获取管理员权限失败:', error)
      set({ isAdmin: false })
    }
  },

  resetAdminStatus: () => set({ isAdmin: null })
}))
