import { Navigate, Route, Routes } from "react-router-dom"
import { Shell } from "./components/Shell"
import { AddFieldPage } from "./pages/AddFieldPage"
import { CreateRulePage } from "./pages/CreateRulePage"
import { CreateTypePage } from "./pages/CreateTypePage"
import { FieldsPage } from "./pages/FieldsPage"
import { NewVersionPage } from "./pages/NewVersionPage"
import { RuleDatesPage } from "./pages/RuleDatesPage"
import { RuleFrame } from "./pages/RuleFrame"
import { RuleLogicPage } from "./pages/RuleLogicPage"
import { RuleOverviewPage } from "./pages/RuleOverviewPage"
import { RuleRunPage } from "./pages/RuleRunPage"
import { RuleSchemaPage } from "./pages/RuleSchemaPage"
import { RulesPage } from "./pages/RulesPage"
import { TypeDetailPage } from "./pages/TypeDetailPage"
import { TypesPage } from "./pages/TypesPage"

export function App() {
  return (
    <Shell>
      <Routes>
        <Route path="/" element={<Navigate to="/types" replace />} />
        <Route path="/types/new" element={<CreateTypePage />} />
        <Route path="/types/:name" element={<TypeDetailPage />} />
        <Route path="/types" element={<TypesPage />} />
        <Route path="/fields/new" element={<AddFieldPage />} />
        <Route path="/fields" element={<FieldsPage />} />
        <Route path="/rules/new" element={<CreateRulePage />} />
        <Route path="/rules/:key/versions/new" element={<NewVersionPage />} />
        <Route path="/rules/:key" element={<RuleFrame />}>
          <Route index element={<RuleOverviewPage />} />
          <Route path="run" element={<RuleRunPage />} />
          <Route path="versions/:version" element={<RuleLogicPage />} />
          <Route path="versions/:version/dates" element={<RuleDatesPage />} />
          <Route path="versions/:version/schema" element={<RuleSchemaPage />} />
        </Route>
        <Route path="/rules" element={<RulesPage />} />
        <Route path="*" element={<Navigate to="/rules" replace />} />
      </Routes>
    </Shell>
  )
}
