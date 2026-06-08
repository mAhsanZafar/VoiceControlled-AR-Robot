const STORAGE_KEY = 'todo_tasks_v1';

const taskForm = document.getElementById('task-form');
const taskInput = document.getElementById('task-input');
const taskList = document.getElementById('task-list');
const clearAllButton = document.getElementById('clear-all');
const emptyState = document.getElementById('empty-state');
const errorText = document.getElementById('input-error');
const totalCount = document.getElementById('total-count');
const completedCount = document.getElementById('completed-count');

let tasks = loadTasks();

function loadTasks() {
  try {
    const saved = localStorage.getItem(STORAGE_KEY);
    const parsed = saved ? JSON.parse(saved) : [];
    if (!Array.isArray(parsed)) return [];
    return parsed
      .filter((task) => task && typeof task.id === 'string' && typeof task.text === 'string')
      .map((task) => ({ id: task.id, text: task.text, completed: Boolean(task.completed) }));
  } catch {
    return [];
  }
}

function saveTasks() {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(tasks));
}

function renderTasks() {
  taskList.innerHTML = '';

  tasks.forEach((task) => {
    const item = document.createElement('li');
    item.className = `task-item${task.completed ? ' completed' : ''}`;

    const checkbox = document.createElement('input');
    checkbox.type = 'checkbox';
    checkbox.checked = task.completed;
    checkbox.setAttribute('aria-label', `Mark task \"${task.text}\" complete`);
    checkbox.addEventListener('change', () => toggleTask(task.id));

    const text = document.createElement('p');
    text.className = 'task-text';
    text.textContent = task.text;

    const actions = document.createElement('div');
    actions.className = 'task-actions';

    const editButton = document.createElement('button');
    editButton.type = 'button';
    editButton.textContent = 'Edit';
    editButton.addEventListener('click', () => editTask(task.id));

    const deleteButton = document.createElement('button');
    deleteButton.type = 'button';
    deleteButton.className = 'delete';
    deleteButton.textContent = 'Delete';
    deleteButton.addEventListener('click', () => deleteTask(task.id));

    actions.append(editButton, deleteButton);
    item.append(checkbox, text, actions);
    taskList.append(item);
  });

  const completed = tasks.filter((task) => task.completed).length;
  totalCount.textContent = `${tasks.length} task${tasks.length === 1 ? '' : 's'}`;
  completedCount.textContent = `${completed} completed`;
  emptyState.style.display = tasks.length ? 'none' : 'block';
  clearAllButton.disabled = tasks.length === 0;
}

function showError(message) {
  errorText.textContent = message;
}

function createTaskId() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function addTask(rawText) {
  const text = rawText.trim();
  if (!text) {
    showError('Task cannot be empty.');
    return;
  }

  tasks.push({
    id: createTaskId(),
    text,
    completed: false,
  });

  saveTasks();
  renderTasks();
  showError('');
}

function toggleTask(taskId) {
  tasks = tasks.map((task) => (task.id === taskId ? { ...task, completed: !task.completed } : task));
  saveTasks();
  renderTasks();
}

function deleteTask(taskId) {
  tasks = tasks.filter((task) => task.id !== taskId);
  saveTasks();
  renderTasks();
}

function editTask(taskId) {
  const task = tasks.find((item) => item.id === taskId);
  if (!task) return;

  const nextText = window.prompt('Edit your task:', task.text);
  if (nextText === null) return;

  const text = nextText.trim();
  if (!text) {
    showError('Task cannot be empty.');
    return;
  }

  tasks = tasks.map((item) => (item.id === taskId ? { ...item, text } : item));
  saveTasks();
  renderTasks();
  showError('');
}

taskForm.addEventListener('submit', (event) => {
  event.preventDefault();
  addTask(taskInput.value);
  taskInput.value = '';
  taskInput.focus();
});

clearAllButton.addEventListener('click', () => {
  if (tasks.length === 0) return;
  const confirmed = window.confirm('Clear all tasks? This cannot be undone.');
  if (!confirmed) return;

  tasks = [];
  saveTasks();
  renderTasks();
  showError('');
});

renderTasks();
