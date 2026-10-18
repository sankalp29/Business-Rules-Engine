import { useCallback, useEffect, useState } from "react"
import { ApiError, api } from "./api"
import type { TypeResponse } from "./model"

export function useTypes() {
  const [types, setTypes] = useState<TypeResponse[] | null>(null)
  const [error, setError] = useState<ApiError | null>(null)

  const reload = useCallback(async () => {
    try {
      const next = await api.listTypes()
      setTypes(next)
      setError(null)
      return next
    } catch (caught) {
      const failure = caught instanceof ApiError ? caught : new ApiError("Could not load types.")
      setError(failure)
      return null
    }
  }, [])

  useEffect(() => {
    void reload()
  }, [reload])

  return { types, error, reload, loading: types === null && error === null }
}

export function usePageTitle(title: string) {
  useEffect(() => {
    document.title = `${title} · Clause`
  }, [title])
}
