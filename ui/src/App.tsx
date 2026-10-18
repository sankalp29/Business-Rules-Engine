import { Navigate, Route, Routes } from "react-router-dom"
import { Shell } from "./components/Shell"
import { AddFieldPage } from "./pages/AddFieldPage"
import { CreateRulePage } from "./pages/CreateRulePage"
import { CreateTypePage } from "./pages/CreateTypePage"
import { FieldsPage } from "./pages/FieldsPage"
import { NewVersionPage } from "./pages/NewVersionPage"
import { RuleDetailPage } from "./pages/RuleDetailPage"
import { RulesPage } from "./pages/RulesPage"
import { TypesPage } from "./pages/TypesPage"

export function App() {
  return (
    <Shell>
      <Routes>
        <Route path="/" element={<Navigate to="/types" replace />} />
        <Route path="/types/new" element={<CreateTypePage />} />
        <Route path="/types" element={<TypesPage />} />
        <Route path="/fields/new" element={<AddFieldPage />} />
        <Route path="/fields" element={<FieldsPage />} />
        <Route path="/rules/new" element={<CreateRulePage />} />
        <Route path="/rules/:key/versions/new" element={<NewVersionPage />} />
        <Route path="/rules/:key/versions/:version" element={<RuleDetailPage />} />
        <Route path="/rules/:key" element={<RuleDetailPage />} />
        <Route path="/rules" element={<RulesPage />} />
        <Route path="*" element={<Navigate to="/rules" replace />} />
      </Routes>
    </Shell>
  )
}
