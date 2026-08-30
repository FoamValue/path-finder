import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import MainLayout from './components/MainLayout';
import Login from './pages/Login';
import ChangePassword from './pages/ChangePassword';
import FileList from './pages/FileList';
import Recycle from './pages/Recycle';
import UserPage from './pages/UserPage';
import DeptPage from './pages/DeptPage';
import LogPage from './pages/LogPage';
import StoragePage from './pages/StoragePage';

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route path="/changePassword" element={<ChangePassword />} />
        <Route element={<MainLayout />}>
          <Route path="/" element={<FileList />} />
          <Route path="/recycle" element={<Recycle />} />
          <Route path="/user" element={<UserPage />} />
          <Route path="/dept" element={<DeptPage />} />
          <Route path="/log" element={<LogPage />} />
          <Route path="/storage" element={<StoragePage />} />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  );
}
