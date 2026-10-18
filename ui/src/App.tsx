import { Navigate, Outlet, Route, Routes } from "react-router-dom"
import { Shell } from "./components/Shell"
import { AddFieldPage } from "./pages/AddFieldPage"
import { CreateRulePage } from "./pages/CreateRulePage"
import { CreateTypePage } from "./pages/CreateTypePage"
import { HomePage } from "./pages/HomePage"
import { NewVersionPage } from "./pages/NewVersionPage"
import { ApprovalPage } from "./pages/ApprovalPage"
import { EditVersionPage } from "./pages/EditVersionPage"
import { RuleFrame } from "./pages/RuleFrame"
import { RuleLogicPage } from "./pages/RuleLogicPage"
import { RuleOverviewPage } from "./pages/RuleOverviewPage"
import { RunRedirect, VersionExecutePage } from "./pages/RuleRunPage"
import { RuleSchemaPage } from "./pages/RuleSchemaPage"
import { VersionPage } from "./pages/VersionPage"
import { RulesPage } from "./pages/RulesPage"
import { TypeDetailPage } from "./pages/TypeDetailPage"
import { TypesPage } from "./pages/TypesPage"

function Desk() {
  return (
    <Shell>
      <Outlet />
    </Shell>
  )
}

export function App() {
  return (
    <Routes>
      <Route path="/" element={<HomePage />} />
      <Route element={<Desk />}>
        <Route path="/types/new" element={<CreateTypePage />} />
        <Route path="/types/:name" element={<TypeDetailPage />} />
        <Route path="/types" element={<TypesPage />} />
        <Route path="/fields/new" element={<AddFieldPage />} />
        <Route path="/fields" element={<Navigate to="/types" replace />} />
        <Route path="/rules/new" element={<CreateRulePage />} />
        <Route path="/rules/:key/versions/new" element={<NewVersionPage />} />
        <Route path="/rules/:key" element={<RuleFrame />}>
          <Route index element={<RuleOverviewPage />} />
          <Route path="run" element={<RunRedirect />} />
          <Route path="versions/:version" element={<VersionPage />}>
            <Route index element={<RuleLogicPage />} />
            <Route path="schema" element={<RuleSchemaPage />} />
            <Route path="execute" element={<VersionExecutePage />} />
            <Route path="edit" element={<EditVersionPage />} />
            <Route path="dates" element={<Navigate to=".." replace />} />
          </Route>
        </Route>
        <Route path="/approval" element={<ApprovalPage />} />
        <Route path="/rules" element={<RulesPage />} />
        <Route path="*" element={<Navigate to="/rules" replace />} />
      </Route>
    </Routes>
  )
}
