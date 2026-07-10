import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AppShell } from './AppShell';

const mutate = vi.fn((_input, options?: { onSuccess?: () => void }) => {
  options?.onSuccess?.();
});

vi.mock('../features/auth', () => ({
  useCurrentUser: () => ({
    data: { user: { displayName: '测试用户' } },
  }),
  useLogout: () => ({
    isPending: false,
    mutate,
  }),
}));

describe('AppShell', () => {
  beforeEach(() => {
    mutate.mockClear();
  });

  it('redirects to login after logout succeeds', async () => {
    const user = userEvent.setup();

    render(
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route
            path="/"
            element={
              <AppShell>
                <main>首页内容</main>
              </AppShell>
            }
          />
          <Route path="/login" element={<main>登录页面</main>} />
        </Routes>
      </MemoryRouter>,
    );

    await user.click(screen.getByRole('button', { name: '退出登录' }));

    expect(mutate).toHaveBeenCalledOnce();
    expect(await screen.findByText('登录页面')).toBeInTheDocument();
  });
});
